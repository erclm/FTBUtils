/******************************************************************************

                            Online Java Compiler.
                Code, Compile, Run and Debug java program online.
Write your code in this editor and press "Run" button to execute it.

*******************************************************************************/

/*
 * FTBUtils Copyright © 2014 Ilya Dynin
 */
package com.idynin.ftbutils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FTBUtils {
  static Options opts = new Options();

  static ArrayList<ModPack> modpacks = null;

  private static Map<String, SimpleEntry<String, Integer>> chEdgeMap =
      new HashMap<String, SimpleEntry<String, Integer>>();

  private static boolean latenciesPopulated = false, freshEdges = false, verbose = false;

  private static final String masterServerURL = "new.creeperrepo.net";
  private static final String modpackMetaPathBase = "/FTB2/static/";
  private static String modpackMetaFile = "modpacks.xml";
  private static String thirdpartyMetaFile = "thirdparty.xml";
  private static String modpackPath = "/FTB2/modpacks";

  private static int exitCode = 0;

  private static Thread shutdownAction = new Thread() {
    @Override
    public void run() {
      System.out.println();
      System.out.println("Terminated.");
    }
  };

  public static void main(String[] args) {
    addOption(twoArgOption("downloadserver", "modpack> <version",
        "Download a Modpack Server. \nVersion optional. \nFetches recommended version if omitted."));
    addOption(twoArgOption("downloadmodpack", "modpack> <version",
        "Download a Modpack. \nVersion optional. \nFetches recommended version if omitted."));
    addOption(oneArgOption("getversion", "modpack", "Get the recommended version of modpack."));
    addOption(oneArgOption("checkversion", "modpack", "Get the recommended version of modpack."));
    addOption(noArgOption("listmodpacks", "List all available modpacks."));
    addOption(twoArgOption("checkversion", "modpack> <version",
        "Checks if the recommended version matches passed version."));

    addOption(oneArgOption("privatepack", "packcode",
        "Perform the requested action in the packcode context."));

    addOption(noArgOption("status", "Print the status of all CreeperHost servers."));

    addOption(noArgOption("help", "Show this help."));
    addOption(noArgOption("v", "Verbose mode."));

    Runtime.getRuntime().addShutdownHook(shutdownAction);

    initialize();

    CommandLineParser parser = new GnuParser();
    try {
      CommandLine cmd = parser.parse(opts, args);
      if (cmd.hasOption("v")) {
        verbose = true;
      }
      if (cmd.hasOption("privatepack")) {
        modpackMetaFile = cmd.getOptionValue("privatepack").trim();
        if (!modpackMetaFile.endsWith(".xml")) {
          modpackMetaFile += ".xml";
        }

        modpackPath = modpackPath.replace("modpacks", "privatepacks");
      }
      if (cmd.hasOption("help")) {
        printHelp();
      } else if (cmd.hasOption("status")) {
        printCHStatus();
      } else if (cmd.hasOption("listmodpacks")) {
        printModpacks();
      } else if (cmd.hasOption("getversion")) {
        System.out.println(getRecommendedVersion(cmd.getOptionValue("getversion")));
      } else if (cmd.hasOption("downloadserver")) {
        downloadModpack(cmd.getOptionValues("downloadserver"), true);
      } else if (cmd.hasOption("downloadmodpack")) {
        downloadModpack(cmd.getOptionValues("downloadmodpack"), false);
      } else if (cmd.hasOption("checkversion")) {
        checkVersion(cmd.getOptionValues("checkversion"));
      } else {
        printHelp();
      }
    } catch (MissingArgumentException e) {
      System.err.println(e.getLocalizedMessage());
      printHelp();
    } catch (UnrecognizedOptionException e) {
      System.err.println(e.getLocalizedMessage());
      printHelp();
    } catch (ParseException e) {
      e.printStackTrace();
    }

    Runtime.getRuntime().removeShutdownHook(shutdownAction);

    System.exit(exitCode);
  }

  private static void addOption(Option o) {
    opts.addOption(o);
  }

  private static void checkVersion(String[] optionValues) {
    if (optionValues.length == 2) {
      String vers = getRecommendedVersion(optionValues[0]);
      if (vers != null) {
        vers = vers.split("\t")[1].trim().replace('_', '.');
        if (optionValues[1].replace('_', '.').equals(vers)) {
          exitCode = 0;
          System.out.println("Versions Match!");
          return;
        } else {
          System.err.println("Versions Do Not Match! New recommended version:\t" + vers);
          exitCode = -1;
        }
      }
    } else {
      System.err.println("Invalid number of arguments. Expected [modpack] [version]");
      exitCode = -1;
    }

  }

  private static void downloadModpack(String[] optionValues, boolean server) {
    String modpack, version = null;
    if (optionValues.length >= 1) {
      modpack = optionValues[0];
      if (optionValues.length == 2) {
        version = optionValues[1];
      }
      downloadModpackServer(modpack, version, server);
    }

  }

  private static void downloadModpackServer(String requestedModpack, String version, boolean server) {
    File outputdir = new File(".");

    if (!outputdir.canWrite()) {
      System.err.println("Cannot write to " + outputdir.getAbsolutePath());
      System.exit(-1);
    }

    if (modpacks == null) {
      populateModpacks();
    }
    requestedModpack = requestedModpack.trim();
    for (ModPack mp : modpacks) {
      if (requestedModpack.equalsIgnoreCase(mp.getName().trim())) {
        if (version != null && !version.equalsIgnoreCase(mp.getVersion())) {
          if (!Arrays.asList(mp.getOldVersions()).contains(version)) {
            System.err.println("Version " + version + " is not valid for modpack " + mp.getName());
            System.exit(-1);
          }
        } else {
          version = mp.getVersion();
        }
        FileOutputStream fos;
        URL mpServerFullLocation;
        URLConnection conn;
        String targetURL = server ? mp.getServerUrl() : mp.getUrl();
        File outputFile =
            new File(mp.getName() + (server ? "-server" : "") + "-" + version + "."
                + FilenameUtils.getExtension(targetURL));
        for (String chServer : serversByAscendingLatency()) {
          try {
            System.out.println("Downloading modpack " + (server ? "server " : "") + mp.getName()
                + " version " + version + " from " + getServerNameFromURL(chServer));
            mpServerFullLocation =
                new URL("http://" + chServer + modpackPath + "/" + mp.getDir() + "/"
                    + version.replace('.', '_') + "/" + targetURL);
            conn = mpServerFullLocation.openConnection();

            final int filesize = conn.getContentLength();
            final String filesizeString = FileUtils.byteCountToDisplaySize(filesize);
            printVerbose("From URL: " + mpServerFullLocation.toString());

            fos = new FileOutputStream(outputFile + ".part");
            final long start = System.currentTimeMillis();
            CountingOutputStream cos = new CountingOutputStream(fos) {

              final String progressFormat = String.format(
                  "\r%70s\rDownload Progress:\t%%6s / %%-6s\t(%%s / sec)", " ");

              String message = "", temp;

              int count;

              @Override
              protected synchronized void beforeWrite(int n) {
                super.beforeWrite(n);

                count = getCount();

                temp =
                    String.format(
                        progressFormat,
                        FileUtils.byteCountToDisplaySize(count),
                        filesizeString,
                        FileUtils.byteCountToDisplaySize(count
                            / (System.currentTimeMillis() - start) * 1000));

                if (!message.equals(temp)) {
                  message = temp;
                  System.out.print(message);
                }
              }

            };
            if (outputFile.exists() && !outputFile.delete()) {
              System.err.println("Unable to delete modpack file " + outputFile.getAbsolutePath());
              System.exit(-1);
            }
            IOUtils.copy(conn.getInputStream(), cos);
            FileUtils.moveFile(new File(outputFile + ".part"), outputFile);
            System.out.println("\nDownloading " + outputFile.getName() + " complete!");
            return;
          } catch (IOException e) {
            System.err.println("Error downloading " + mp.getName() + " from " + chServer);
            continue;
          }

        }
        System.err.println("Error downloading modpack " + mp.getName());
      }
    }
    System.err.println("Modpack " + requestedModpack + " not found");
    exitCode = -1;
  }

  private static HashMap<String, SimpleEntry<String, Integer>> fetchEdgesFromServer(String server,
      int timeout) {
    JsonParser jp = new JsonParser();
    HashMap<String, SimpleEntry<String, Integer>> edgemap =
        new HashMap<String, SimpleEntry<String, Integer>>();

    InputStream is;
    URLConnection con;
    try {
      con = new URL("http://" + server + "/edges.json").openConnection();
      con.setConnectTimeout(timeout);
      is = con.getInputStream();
      @SuppressWarnings("unchecked")
      JsonObject edges =
          jp.parse(StringUtils.join(IOUtils.readLines(is, Charset.forName("UTF-8"))))
              .getAsJsonArray().get(0).getAsJsonObject();
      for (Entry<String, JsonElement> edge : edges.entrySet()) {
        edgemap.put(edge.getKey(), new SimpleEntry<String, Integer>(edge.getValue().getAsString(),
            Integer.MAX_VALUE));
      }

      if (edgemap.size() > 0)
        return edgemap;
    } catch (Exception e) {
    }
    return null;
  }

  private static void fetchFreshEdges() {
    HashMap<String, SimpleEntry<String, Integer>> edgemap = null;
    printVerbose("Fetching fresh edges...");
    freshEdges = false;
    if ((edgemap = fetchEdgesFromServer(masterServerURL, 600)) != null) {
      chEdgeMap = edgemap;
      latenciesPopulated = false;
      freshEdges = true;

    } else {

      edgemap = new HashMap<String, SimpleEntry<String, Integer>>();

      ArrayList<SimpleEntry<String, Integer>> servs = new ArrayList<SimpleEntry<String, Integer>>();
      servs.addAll(chEdgeMap.values());

      Collections.shuffle(servs);

      for (SimpleEntry<String, Integer> defaultedge : servs) {
        if ((edgemap = fetchEdgesFromServer(defaultedge.getKey(), 1200)) != null) {
          chEdgeMap = edgemap;
          latenciesPopulated = false;
          freshEdges = true;
          break;
        }

      }
    }
    if (freshEdges) {
      printVerbose("Edges fetched successfully");
    } else {
      printVerbose("Error fetching edges, using static edges");
    }
  }

  private static String getLowestLatencyServer() {
    return serversByAscendingLatency()[0];
  }

  private static String getRecommendedVersion(String requestedModpack) {
    if (modpacks == null) {
      populateModpacks();
    }
    requestedModpack = requestedModpack.trim();
    for (ModPack mp : modpacks) {
      if (requestedModpack.equalsIgnoreCase(mp.getName().trim()))
        return mp.getName() + " recommended version:\t" + mp.getVersion();
    }
    System.err.println("Invalid modpack: " + requestedModpack);
    exitCode = -1;
    return "";
  }

  private static String getServerNameFromURL(String url) {
    for (Entry<String, SimpleEntry<String, Integer>> e : chEdgeMap.entrySet()) {
      if (e.getValue().getKey().equalsIgnoreCase(url))
        return e.getKey();
    }
    return url;
  }

  private static void initialize() {
    try {
      InputStream is = FTBUtils.class.getResourceAsStream("edges.json");
      @SuppressWarnings("unchecked")
      JsonObject edges =
          new JsonParser().parse(StringUtils.join(IOUtils.readLines(is, Charset.forName("UTF-8"))))
              .getAsJsonArray().get(0).getAsJsonObject();
      for (Entry<String, JsonElement> edge : edges.entrySet()) {
        chEdgeMap.put(edge.getKey(), new SimpleEntry<String, Integer>(
            edge.getValue().getAsString(), Integer.MAX_VALUE));
      }
    } catch (Exception e) {
      printVerbose("Error loading internal edges.json");
      chEdgeMap.clear();
      // @formatter:off
      chEdgeMap.put(
          "Staging",
          new SimpleEntry<String, Integer>(
              "87.117.245.3",
              Integer.MAX_VALUE)
          );
      chEdgeMap.put(
          "San Jose",
          new SimpleEntry<String, Integer>(
              "8.17.252.26",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Sydney",
          new SimpleEntry<String, Integer>(
              "43.245.167.43",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Grantham",
          new SimpleEntry<String, Integer>(
              "185.57.191.130",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Buffalo",
          new SimpleEntry<String, Integer>(
              "198.23.140.130",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Atlanta",
          new SimpleEntry<String, Integer>(
              "69.31.134.154",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Seattle",
          new SimpleEntry<String, Integer>(
              "198.23.149.146",
              Integer.MAX_VALUE));
      chEdgeMap.put(
          "Maidenhead",
          new SimpleEntry<String, Integer>(
              "78.129.201.23",
              Integer.MAX_VALUE));
      // @formatter:on
    }

  }

  private static void populateLatencies() {
    populateLatencies(25);

  }

  private static void populateLatencies(final int latencyEarlyCutoff) {
    if (!freshEdges) {
      fetchFreshEdges();
    }

    Iterator<Entry<String, SimpleEntry<String, Integer>>> iter = chEdgeMap.entrySet().iterator();

    Entry<String, SimpleEntry<String, Integer>> edge;

    class PingRequest implements Callable<Entry<String, SimpleEntry<String, Integer>>> {

      Entry<String, SimpleEntry<String, Integer>> edge;

      int timeout = 1000;
      int numpings = 3;

      public PingRequest(Entry<String, SimpleEntry<String, Integer>> target) {
        edge = target;
      }

      @Override
      public Entry<String, SimpleEntry<String, Integer>> call() throws Exception {
        InetSocketAddress addr;
        long start, stop, lat;
        int sum = 0, count = 0, pingTime = -1;
        addr = new InetSocketAddress(edge.getValue().getKey(), 80);

        printVerbose("Pinging " + addr);
        for (int i = 0; i < numpings; i++) {
          try {
            start = System.currentTimeMillis();
            new Socket().connect(addr, timeout);
            stop = System.currentTimeMillis();
            lat = stop - start;
            sum += lat;
            count++;
            if (lat < latencyEarlyCutoff) {
              break;
            }
          } catch (IOException e) {
            continue;
          }
        }
        if (sum > 0) {
          pingTime = sum / count;
          edge.getValue().setValue(pingTime);
        }
        printVerbose("Pingtime for " + addr + " is " + pingTime);
        return edge;
      }

    }

    ExecutorService execpool = Executors.newCachedThreadPool();

    List<Future<Entry<String, SimpleEntry<String, Integer>>>> reqs =
        new ArrayList<Future<Entry<String, SimpleEntry<String, Integer>>>>();
    while (iter.hasNext()) {
      edge = iter.next();
      reqs.add(execpool.submit(new PingRequest(edge)));
    }

    Entry<String, SimpleEntry<String, Integer>> temp;
    while (!reqs.isEmpty()) {
      for (int i = 0; i < reqs.size(); i++) {
        if (reqs.get(i).isDone()) {
          try {
            temp = reqs.get(i).get();
            chEdgeMap.put(getServerNameFromURL(temp.getKey()), temp.getValue());
            reqs.remove(i);
            if (temp.getValue().getValue() < latencyEarlyCutoff) {
              break;
            }
            latenciesPopulated = true;
          } catch (InterruptedException e) {
            e.printStackTrace();
          } catch (ExecutionException e) {
            e.printStackTrace();
          }

        }
      }
    }
    execpool.shutdownNow();
  }

  private static void populateModpacks() {
    ModPack.populateModpacks(getLowestLatencyServer(), modpackMetaPathBase + modpackMetaFile,
        new ModPackFilter() {
          @Override
          public boolean accept(ModPack modpack) {
            return modpack.getAuthor().equalsIgnoreCase("The FTB Team");
          }
        });

    ModPack.populateModpacks(getLowestLatencyServer(), modpackMetaPathBase + thirdpartyMetaFile,
        new ModPackFilter() {
          @Override
          public boolean accept(ModPack modpack) {
            return true;
          }
        });
    modpacks = ModPack.getPackArray();

  }

  private static void printCHStatus() {

    if (!latenciesPopulated) {
      populateLatencies(9999);
    }
    String format = "%-25s\t%-35s\t%11s";
    System.out.println(String.format(format, "Server", "Address", "Latency"));
    String name, addr;
    int lat;
    ArrayList<Entry<String, SimpleEntry<String, Integer>>> servers =
        new ArrayList<Map.Entry<String, SimpleEntry<String, Integer>>>(chEdgeMap.entrySet());
    Collections.sort(servers, new Comparator<Entry<String, SimpleEntry<String, Integer>>>() {

      @Override
      public int compare(Entry<String, SimpleEntry<String, Integer>> o1,
          Entry<String, SimpleEntry<String, Integer>> o2) {
        return o1.getKey().compareTo(o2.getKey());
      }
    });
    for (Entry<String, SimpleEntry<String, Integer>> serv : servers) {
      name = serv.getKey();
      addr = serv.getValue().getKey();
      lat = serv.getValue().getValue();
      System.out.println(String.format(format, name, addr, lat == Integer.MAX_VALUE ? "Unreachable"
          : lat));

    }

  }

  private static void printHelp() {
    HelpFormatter hf = new HelpFormatter();
    String header;
    // @formatter:off
    header = "\n88888888888 888888888888 88888888ba  88        88         88 88            \n" +
        "88               88      88      \"8b 88        88   ,d    \"\" 88            \n" +
        "88               88      88      ,8P 88        88   88       88            \n" +
        "88aaaaa          88      88aaaaaa8P' 88        88 MM88MMM 88 88 ,adPPYba,  \n" +
        "88\"\"\"\"\"          88      88\"\"\"\"\"\"8b, 88        88   88    88 88 I8[    \"\"  \n" +
        "88               88      88      `8b 88        88   88    88 88  `\"Y8ba,   \n" +
        "88               88      88      a8P Y8a.    .a8P   88,   88 88 aa    ]8I  \n" +
        "88               88      88888888P\"   `\"Y8888Y\"'    \"Y888 88 88 `\"YbbdP\"'  \n\n"
        + "FTBUtils\nCopyright © 2014 Ilya Dynin\n";
    // @formatter:on
    System.out.println(header);
    hf.printHelp("java -jar ftbutils.jar [options]", opts);
    System.out.println();
  }

  private static void printModpacks() {
    if (modpacks == null) {
      populateModpacks();
    }
    if (modpacks.size() > 0) {
      String headformat = "%-35s\t%-20s\t%-10s\t%-12s";
      String format = "%-35s\t%-20s\t%10s\t%-12s";
      System.out.println(String.format(headformat, "Pack Name", "Author", "MC Version",
          "Pack Version"));
      for (ModPack mp : modpacks) {
        System.out.println(String.format(format, mp.getName(), mp.getAuthor(), mp.getMcVersion(),
            mp.getVersion()));
      }
    } else {
      System.out.println("No modpacks found...");
    }
  }

  private static void printVerbose(Object m) {
    if (verbose) {
      System.out.println(m.toString());
    }
  }

  private static String[] serversByAscendingLatency() {
    if (!latenciesPopulated) {
      populateLatencies();
    }
    ArrayList<SimpleEntry<String, Integer>> servs = new ArrayList<SimpleEntry<String, Integer>>();
    servs.addAll(chEdgeMap.values());
    Collections.sort(servs, new Comparator<SimpleEntry<String, Integer>>() {

      @Override
      public int compare(SimpleEntry<String, Integer> o1, SimpleEntry<String, Integer> o2) {
        return o1.getValue().compareTo(o2.getValue());
      }
    });
    String[] out = new String[servs.size()];
    for (int i = 0; i < servs.size(); i++) {
      out[i] = servs.get(i).getKey();
    }
    return out;

  }

  private static Option noArgOption(String option, String description) {
    OptionBuilder.withDescription(description);
    return OptionBuilder.create(option);
  }

  private static Option oneArgOption(String option, String argName, String description) {
    OptionBuilder.hasArg();
    OptionBuilder.withArgName(argName);
    OptionBuilder.withDescription(description);
    return OptionBuilder.create(option);
  }

  private static Option twoArgOption(String option, String argName, String description) {
    OptionBuilder.hasArgs(2);
    OptionBuilder.withArgName(argName);
    OptionBuilder.withDescription(description);
    return OptionBuilder.create(option);
  }

}

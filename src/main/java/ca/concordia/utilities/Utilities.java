package ca.concordia.utilities;

import static ca.concordia.utilities.GET.call_get;
import static ca.concordia.utilities.Help.helpCommand;
import static ca.concordia.utilities.Help.printHelp;
import static ca.concordia.utilities.POST.call_post_socket;
import static ca.concordia.utilities.POST.call_post_udp;
import static ca.concordia.utilities.Parameters.isValidCall;
import static java.nio.channels.SelectionKey.OP_READ;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.json.simple.parser.ParseException;

public class Utilities {

  public static int redirectionCount = 0;


  public static void main(String args[]) {
    JSONObject params = null;
    try {
      if (helpCommand(args)) {
        if (args.length == 1) {
          printHelp("help");
        } else if (isValidCall(args[1])) {
          printHelp(args[1]);
        } else {
          printHelp("help");
        }
      } else {
        Parameters parameters = new Parameters();
        parameters.getParameters(args);
        if (parameters.getCallingMethod().equalsIgnoreCase("GET")) {
          call_get(parameters);
        } else if (parameters.getCallingMethod().equalsIgnoreCase("POST")) {
          call_post_udp(parameters);
        } else {
          throw new InCorrectValues("unsupported operation");
        }
      }

    } catch (InCorrectValues | ParseException inCorrectValues) {
      System.out.println(inCorrectValues.getMessage());
    } catch (IOException e) {
      e.printStackTrace();
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }

  }


  public static void writeInFile(String response, String outPutFileName)
      throws InCorrectValues {
    BufferedWriter bwr = null;
    try {
      bwr = new BufferedWriter(new FileWriter(new File(outPutFileName)));
      bwr.write(response);
      bwr.flush();
      bwr.close();
    } catch (IOException e) {
      throw new InCorrectValues("Can not write output in given file");
    }

  }

  public static int getPort(URI uri, String protocol) {
    int port = uri.getPort();
    if (port == -1) {
      if (protocol.equals("http")) {
        port = 80; // http port
      } else if (protocol.equals("https")) {
        port = 443; // https port
      }
    }
    return port;
  }

  public static String handleOutput(Parameters parameters, BufferedReader rd, String line,
      StringBuilder verboseDetails, StringBuilder nonVerboseDetails, boolean foundPartition,
      boolean redirectionFound, String host, String protocol)
      throws IOException, URISyntaxException, InCorrectValues, ParseException {
    while (line != null && rd.ready()) {
      if (!redirectionFound && line.contains("302") && line.contains("HTTP")) {
        System.out.println(line);
        BufferedReader copy = new BufferedReader(rd);

        redirectionFound = true;
        if (Utilities.redirectionCount < 5) {
          String url = findRedirectionURL(copy, host, protocol, parameters);
          parameters.redirectURL = url;
          System.out.println(parameters.url + " is redirected to " + parameters.redirectURL);
          if (parameters.redirectURL == null) {
            throw new InCorrectValues("Redirection URL Not Found");
          }
          parameters.url = parameters.redirectURL;
          Utilities.redirectionCount++;
          if (parameters.getCallingMethod().equalsIgnoreCase("GET")) {
            call_get(parameters);
          } else {
            call_post_socket(parameters);
          }


        } else {
          throw new InCorrectValues("Redirction can not exceed more than 5 time");
        }

      }
      if (rd.ready()) {
        line = rd.readLine();
      }

      if (!foundPartition && line.equals(StringUtils.EMPTY)) {
        foundPartition = true;
      }
      if (!foundPartition) {
        verboseDetails.append(line).append("\n");
      } else {
        nonVerboseDetails.append(line).append("\n");
      }
    }
    String output = new String();
    if (parameters.verboseOption) {
      output += verboseDetails.toString();
    }
    output += nonVerboseDetails.toString();
    if (!redirectionFound) {
      System.out.println(output);
    }

    return output;
  }

  private static String findRedirectionURL(BufferedReader copy, String host, String protocol,
      Parameters parameters)
      throws IOException {
    String line = "";
    String url = ";";
    while (line != null && copy.ready()) {
      line = copy.readLine();
      if (parameters.verboseOption) {
        System.out.println(line);
      }
      if (line.contains("Location:")) {
        String location[] = line.split(":");
        url = protocol + "://" + host.trim() + location[1];
        break;
      }
    }
    return url.replaceAll("\\s+", "");
  }

  static boolean doThreeWayHandshake(Parameters parameters, DatagramChannel channel)
      throws InCorrectValues, URISyntaxException, IOException {

    if (parameters.getUrl() != null) {

      String urlStr = parameters.getUrl();
      URI uri = new URI(urlStr);
      String host = uri.getHost();
      String path = uri.getRawPath();
      if (path == null || path.length() == 0) {
        path = "/";
      }
      path = getQuery(uri, path);

      String protocol = uri.getScheme();
      int port = getPort(uri, protocol);

      SocketAddress routerAddress = new InetSocketAddress("localhost", 3000);
      InetSocketAddress serverAddress = new InetSocketAddress(host, port);

      Packet p = new Packet.Builder()
          .setType(2)
          .setSequenceNumber(1L)
          .setPortNumber(serverAddress.getPort())
          .setPeerAddress(serverAddress.getAddress())
          .setPayload("SYNC".getBytes())
          .create();
      channel.send(p.toBuffer(), routerAddress);

      System.out.println("SYNC REQUEST sent to server");

      channel.configureBlocking(false);
      Selector selector = Selector.open();
      channel.register(selector, OP_READ);
      System.out.println("Waiting for the ACK from server");
      selector.select(1000);

      Set<SelectionKey> keys = selector.selectedKeys();
      if (keys.isEmpty()) {
        System.out.println("No response from server. Connection can not be established.");
        return false;
      }

      ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
      SocketAddress router = channel.receive(buf);
      buf.flip();
      Packet resp = Packet.fromBuffer(buf);
      System.out.println("Packet: {}" + resp);
      System.out.println("Router: {}" + router);
      String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);

      if (!payload.equalsIgnoreCase("ACK")) {
        return false;
      }
      System.out.println("ACK Received from Server");

      p = new Packet.Builder()
          .setType(1)
          .setSequenceNumber(1L)
          .setPortNumber(serverAddress.getPort())
          .setPeerAddress(serverAddress.getAddress())
          .setPayload("ACK".getBytes())
          .create();
      channel.send(p.toBuffer(), routerAddress);
      System.out.println("ACK sent back to server. \n");
      return true;


    }

    return false;
  }

  private static String getQuery(URI uri, String path) {
    String query = uri.getRawQuery();
    if (query != null && query.length() > 0) {
      path += "?" + query;
    }
    return path;
  }
}



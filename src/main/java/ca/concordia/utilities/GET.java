package ca.concordia.utilities;

import static ca.concordia.utilities.Utilities.getPort;
import static ca.concordia.utilities.Utilities.writeInFile;
import static java.nio.channels.SelectionKey.OP_READ;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.Range;
import org.json.simple.parser.ParseException;

public class GET {

  private static int windowSize = 8;
  static Range<Long> myRange = Range.between(0L, new Long(windowSize));
  static Set<Long> receivedPackets = new HashSet<>();

  public static void call_get(Parameters parameters)
      throws IOException, InCorrectValues, ParseException, URISyntaxException {
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
      Socket socket = null;
      PrintWriter request = null;
      InputStream inStream = null;
      BufferedReader rd = null;

      receivedPackets = new HashSet<>();

      try {
        SocketAddress routerAddress = new InetSocketAddress("localhost", 3000);
        InetSocketAddress serverAddress = new InetSocketAddress(host, port);

        try (DatagramChannel channel = DatagramChannel.open()) {
          StringBuilder msg = new StringBuilder();
          StringBuilder headers = new StringBuilder();
          if (parameters.hasHeader) {
            for (String key : parameters.getHeaders().keySet()) {
              headers.append(key + ": " + parameters.getHeaders().get(key).toString() + "\r\n");
            }
            headers.append("\r\n");
            msg.append("GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                headers.toString());
          } else {
            msg.append("GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "\r\n");

          }
          Packet p = new Packet.Builder()
              .setType(0)
              .setSequenceNumber(1L)
              .setPortNumber(serverAddress.getPort())
              .setPeerAddress(serverAddress.getAddress())
              .setPayload(msg.toString().getBytes())
              .create();
          if (!Utilities.doThreeWayHandshake(parameters, channel)) {
            throw new InCorrectValues("Can not perform three way hand shake");
          }

          channel.send(p.toBuffer(), routerAddress);

          System.out.println("Sending \"{}\" to router at {}" + msg + routerAddress);

          // Try to receive a packet within timeout.
          channel.configureBlocking(false);
          Selector selector = Selector.open();
          channel.register(selector, OP_READ);
          System.out.println("Waiting for the response");
          selector.select(5000);

          Set<SelectionKey> keys = selector.selectedKeys();
          if (keys.isEmpty()) {
            System.out.println("No response after timeout");
            return;
          }

          // We just want a single response
          boolean keepGoing = true;
          ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);

          myRange = Range.between(0L, new Long(windowSize));
          while (keepGoing) {
            Thread.sleep(200);
            SocketAddress router = channel.receive(buf);
            buf.flip();
            if (buf.limit() == 0) {
              keepGoing = false;
              break;
            }
            if (keepGoing) {
              Packet resp = Packet.fromBuffer(buf);

              System.out.println(resp.getSequenceNumber());
              System.out.println(myRange.getMinimum() + " - " + myRange.getMaximum());
              if (isSequenceinCurrentWindow(resp.getSequenceNumber(), myRange)) {
                //given condition in below if will discard the duplicate packet received

                if (receivedPackets.add(resp.getSequenceNumber())) {
                  sendAck(resp, serverAddress, channel, routerAddress);
                  if (isPreviousPacketReceived(resp.getSequenceNumber())) {
                    myRange = Range.between(myRange.getMinimum() + 1,
                        myRange.getMaximum() + 1);
                  }
                  String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
                  if (parameters.hasOutputFile) {
                    writeInFile(payload, parameters.outPutFileName);
                  }
                  if (parameters.verboseOption) {
                    System.out.println(payload);
                  } else {
                    System.out.println(payload);
                  }
                } else {
                  System.out.println("Duplicate packet discarded.");
                }
                keys.clear();
              }

              buf.clear();
            }
          }
          System.out.println("Packets Accepted -> " + receivedPackets);
        }


      } catch (Exception e) {
        throw new InCorrectValues(e.getMessage());
      } finally {
        if (rd != null) {
          rd.close();
        }
        if (inStream != null) {
          inStream.close();
        }
        if (request != null) {
          request.close();
        }
        if (socket != null) {
          socket.close();
        }
      }


    } else {
      throw new InCorrectValues("URL is Empty");
    }
  }

  private static void sendAck(Packet resp, InetSocketAddress serverAddress,
      DatagramChannel channel, SocketAddress routerAddress) throws IOException {
    System.out.println("Sending Ack starts ");
    System.out.println("Sending ack for the " + resp.getSequenceNumber());
    Packet p = new Packet.Builder()
        .setType(1)
        .setSequenceNumber(resp.getSequenceNumber())
        .setPortNumber(serverAddress.getPort())
        .setPeerAddress(serverAddress.getAddress())
        .setPayload("".getBytes())
        .create();

    System.out.println(channel.send(p.toBuffer(), routerAddress));

  }

  private static String getQuery(URI uri, String path) {
    String query = uri.getRawQuery();
    if (query != null && query.length() > 0) {
      path += "?" + query;
    }
    return path;
  }

  private static boolean isSequenceinCurrentWindow(long sequenceNumber,
      Range<Long> range) {
    return range.contains(sequenceNumber);
  }

  private static boolean isPreviousPacketReceived(long sequenceNumber) {
    System.out.println(receivedPackets);
    for (int i = 0; i < sequenceNumber; i++) {
      System.out.println("Contains : " + receivedPackets.contains((long) i));
      if (!receivedPackets.contains((long) i)) {
        return false;
      }
    }

    return true;
  }

}

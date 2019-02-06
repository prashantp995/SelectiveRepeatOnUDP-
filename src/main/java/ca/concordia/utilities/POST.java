package ca.concordia.utilities;

import static ca.concordia.utilities.Utilities.getPort;
import static ca.concordia.utilities.Utilities.handleOutput;
import static ca.concordia.utilities.Utilities.writeInFile;
import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashSet;
import java.util.Set;

public class POST {

  public static void call_post_udp(Parameters parameters)
      throws URISyntaxException, IOException {
    String urlStr = parameters.getUrl();
    URI uri = new URI(urlStr);
    String host = uri.getHost();
    String path = uri.getRawPath();
    if (path == null || path.length() == 0) {
      path = "/";
    }
    String protocol = uri.getScheme();
    int port = getPort(uri, protocol);

    String params = new String();

    if (parameters.hasFile || parameters.hasInlineData) {
      params += parameters.data;
    }

    StringBuilder request = new StringBuilder();
    request.append("POST " + path + " HTTP/1.1\r\n" +
        "Host: " + host + "\r\n" + "Content-Length: " + String.valueOf(params.length())
        + "\r\n");
    StringBuilder headers = new StringBuilder();
    SocketAddress routerAddress = new InetSocketAddress("localhost", 3000);
    InetSocketAddress serverAddress = new InetSocketAddress(host, port);

    if (parameters.hasHeader) {
      for (String key : parameters.getHeaders().keySet()) {
        headers.append(key + ": " + parameters.getHeaders().get(key).toString() + "\r\n");
      }
      headers.append("\r\n");
      request.append(headers.toString());

    } else {
      request.append("\r\n");
    }
    request.append(new String(params.getBytes(), UTF_8));

    try (DatagramChannel channel = DatagramChannel.open()) {
      Packet p = new Packet.Builder()
          .setType(0)
          .setSequenceNumber(1L)
          .setPortNumber(serverAddress.getPort())
          .setPeerAddress(serverAddress.getAddress())
          .setPayload(new String(request.toString().getBytes(), UTF_8).getBytes())
          .create();
      if (!Utilities.doThreeWayHandshake(parameters, channel)) {
        throw new InCorrectValues("Can not perform three way hand shake");
      }
      Packet packets[] = getChunksofPacket(request.toString().getBytes(), p);
      for (Packet packet : packets) {
        channel.send(packet.toBuffer(), routerAddress);
        packet.setTimestart(System.currentTimeMillis());
        // System.out.println("Sending \"{}\" to router at {}" + request + routerAddress);
      }

      // Try to receive a packet within timeout.
      channel.configureBlocking(false);
      Selector selector = Selector.open();
      channel.register(selector, OP_READ);
      System.out.println("Waiting for the response");
      selector.select(5000);

      Set<SelectionKey> keys = selector.selectedKeys();
      if (keys.isEmpty()) {
        System.out.println("No response after timeout");
        // return;
      }

      boolean keepgoing = true;
      ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
      Set<Long> ackReceived = new HashSet<>();
      while (buf.limit() > 0) {
        Thread.sleep(50);
        if (keepgoing) {
          SocketAddress router = channel.receive(buf);
          buf.flip();
          if (buf.limit() == 0) {
            Thread.sleep(100);
            buf.flip();
            continue;
          }
          Packet resp = Packet.fromBuffer(buf);
          if (resp.getType() == 0 && ackReceived.size() == packets.length) {
            System.out.println("Packet: {}" + resp);
            System.out.println("Router: {}" + router);
            String payload = new String(resp.getPayload(), UTF_8);
            System.out.println(payload);
            keepgoing = false;
            if (parameters.hasOutputFile) {
              writeInFile(payload, parameters.outPutFileName);
            }
            break;

          } else {

            reTransmitIfRequire(channel, router, packets, ackReceived);
            ackReceived.add(resp.getSequenceNumber());
            System.out.println("Ack received for the " + ackReceived);
          }
          buf.clear();
        }

      }


    } catch (InCorrectValues | InterruptedException inCorrectValues) {
      inCorrectValues.getMessage();
    }

  }


  public static Packet[] getChunksofPacket(byte[] message, Packet packet) {
    int messageSize = message.length;
    int NumberOfPacketsToSend = (messageSize / Packet.MAX_DATA) + 1;
    int offset = 0;

    Packet[] packets;

    packets = new Packet[NumberOfPacketsToSend];
    long sequenceNumber = 0;

    for (int i = 0, pLen = packets.length; i < pLen; i++) {

      byte[] one_chunk = new byte[Packet.MAX_DATA];
      int len =
          ((messageSize - offset) < Packet.MAX_DATA) ? (messageSize - offset) : Packet.MAX_DATA;
      System.arraycopy(message, offset, one_chunk, 0, len);
      int type = 0; //for data
      Packet p = new Packet.Builder()
          .setType(type)
          .setSequenceNumber(sequenceNumber++)
          .setPortNumber(packet.getPeerPort())
          .setPeerAddress(packet.getPeerAddress())
          .setPayload(one_chunk)
          .create();
      packets[i] = p;
      offset = offset + len;
    }
    return packets;
  }

  public static void call_post_socket(Parameters parameters)
      throws URISyntaxException, IOException, InCorrectValues {
    if (parameters.getUrl() != null) {

      String urlStr = parameters.getUrl();
      URI uri = new URI(urlStr);
      String host = uri.getHost();
      String path = uri.getRawPath();
      if (path == null || path.length() == 0) {
        path = "/";
      }

      String protocol = uri.getScheme();
      int port = getPort(uri, protocol);
      Socket socket = null;
      PrintWriter request = null;
      InputStream inStream = null;
      BufferedReader rd = null;
      BufferedWriter wr = null;
      try {
        String params = new String();

        if (parameters.hasFile || parameters.hasInlineData) {
          for (String key : parameters.inLinedata.keySet()) {
            params += ("&" + URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder
                .encode(parameters.inLinedata.get(key).toString(), "UTF-8"));
          }
        }
        if (params.startsWith("&")) {
          params = params.replaceFirst("&", "");
        }
        socket = new Socket(host, port);

        // Content Length is must for the post request

        wr = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF8"));
        wr.write("POST " + path + " HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" + "Content-Length: " + String.valueOf(params.length())
            + "\r\n");
        StringBuilder headers = new StringBuilder();
        if (parameters.hasHeader) {
          for (String key : parameters.getHeaders().keySet()) {
            headers.append(key + ": " + parameters.getHeaders().get(key).toString() + "\r\n");
          }
          headers.append("\r\n");
          wr.write(headers.toString());

        } else {
          wr.write("\r\n");
        }

        wr.write(params);
        wr.flush();
        rd = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        StringBuilder verboseDetails = new StringBuilder();
        StringBuilder nonVerboseDetails = new StringBuilder();
        line = rd.readLine();
        verboseDetails.append(line + "\n");
        StringBuffer response = new StringBuffer();
        boolean foundPartition = false;
        boolean redirectionFound = false;
        String output = handleOutput(parameters, rd, line, verboseDetails, nonVerboseDetails,
            foundPartition,
            redirectionFound, host, protocol);
        if (parameters.hasOutputFile) {
          writeInFile(output, parameters.outPutFileName);
        }

      } catch (Exception e) {
        e.printStackTrace();
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
        if (wr != null) {
          wr.close();
        }
      }
    }

  }

  private static void reTransmitIfRequire(DatagramChannel channel, SocketAddress router,
      Packet[] packets, Set<Long> receivedAck) throws IOException {
    for (Packet packet : packets) {
      long ackDelayedBY = System.currentTimeMillis() - packet.getTimestart();
      if (packet.getTimestart() != 0L
          && ackDelayedBY > 200L && !receivedAck.contains(packet.getSequenceNumber())) {
        System.out.println(
            "Packet Delayed by" + ackDelayedBY + "Retransmit Packet " + packet.getSequenceNumber());
        channel.send(packet.toBuffer(), router);
        packet.setTimestart(System.currentTimeMillis());
      }
    }
  }


}

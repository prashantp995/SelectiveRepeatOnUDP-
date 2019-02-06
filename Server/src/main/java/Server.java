package main.java;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import org.apache.commons.lang3.Range;

public class Server implements Runnable {

  static File WEB_ROOT;
  static final String DEFAULT_FILE = "default.html";
  static final String FILE_NOT_FOUND = "fileNotFound.html";
  static final String METHOD_NOT_SUPPORTED = "not_supported.html";
  static boolean verbose;
  public static int totalRequestReceived = 0;
  private static boolean postProcessStarts = false;
  public String payload;
  static FileChannel fileChannel = null;
  static FileLock lock = null;
  static int windowSize = 8;
  static Range<Long> myRange = Range.between(0L, new Long(windowSize));
  static String requestedFile = "";
  static boolean requestFileChange = false;
  static Set<Long> receivedPackets = new HashSet<>();

  public static void main(String[] args) {
    try {
      if (helpCommand(args)) {
        System.out.println("[-v] [-p PORT] [-d PATH-TO-DIR]\n"
            + "-v Prints debugging messages.\n"
            + "-p Specifies the port number that the server will listen and serve at.\n"
            + "Default is 8080.\n"
            + "-d Specifies the directory that the server will use to read/write");
      } else {
        ServerParameters serverParameters = new ServerParameters();
        try {
          serverParameters = serverParameters.getParameters(args);
        } catch (InCorrectValues inCorrectValues) {
          inCorrectValues.printStackTrace();
        }
        WEB_ROOT = new File(serverParameters.dirPath);
        verbose = serverParameters.verboseOption;
        try (DatagramChannel channel = DatagramChannel.open()) {
          channel.socket().setReuseAddress(true);
          channel.bind(new InetSocketAddress(serverParameters.port));
          System.out.println("Server is listening at {}" + channel.getLocalAddress());
          ByteBuffer buf = ByteBuffer
              .allocate(Packet.MAX_LEN)
              .order(ByteOrder.BIG_ENDIAN);
          for (; ; ) {
            buf.clear();
            SocketAddress router = channel.receive(buf);
            // Parse a packet from the received raw data.
            buf.flip();
            Packet packet = Packet.fromBuffer(buf);
            buf.flip();
            String payload = new String(packet.getPayload(), UTF_8);
            if (packet.getType() == 2) {
              if (payload.equalsIgnoreCase("SYNC")) {
                Packet resp = packet.toBuilder()
                    .setType(1)
                    .setPayload("ACK".getBytes())
                    .create();
                channel.send(resp.toBuffer(), router);
              }
            }
            if (packet.getType() == 0) {

              StringTokenizer tokenizer = new StringTokenizer(payload, " ");
              String method;
              if (!tokenizer.hasMoreTokens()) {
                method = "POST";
              } else {
                method = tokenizer.nextToken();
              }
              if (requestedFile.length() == 0) {
                if (tokenizer.hasMoreTokens()) {
                  requestedFile = tokenizer.nextToken();
                } else {
                  requestedFile = "default.txt";
                }
                System.out.println(requestedFile);

              }
              String headerLine;

              if (method.equals("GET")) {
                if (requestedFile.endsWith("/")) {
                  File[] file = WEB_ROOT.listFiles();
                  StringBuilder listOfFiles = new StringBuilder();
                  for (File f : file) {
                    if (!f.isDirectory()) {
                      listOfFiles.append(f.getName() + "\n");
                    }

                  }
                  Packet resp = packet.toBuilder()
                      .setPayload(listOfFiles.toString().getBytes())
                      .create();
                  channel.send(resp.toBuffer(), router);

                } else {

                  File file = new File(WEB_ROOT, requestedFile);
                  StringBuilder response = new StringBuilder();
                  int fileLength = (int) file.length();
                  String content = getContentType(requestedFile);
                  System.out.println("Content is " + content);
                  String contentDisposition;
                  if (content.equals("text/plain")) {
                    contentDisposition = "inline";
                  } else {
                    contentDisposition =
                        "attachment; filename=" + WEB_ROOT + requestedFile.replace("/", "\\") + ";";
                  }
                  RandomAccessFile raf = new RandomAccessFile(file.getName(), "rw");
                  FileChannel fileChannel = raf.getChannel();
                  FileLock lock = null;
                  try {

                    lock = fileChannel.tryLock();
                    if (lock == null) {
                      if (verbose) {
                        System.out.println(
                            file.getName()
                                + "is being used can not perform write operation on it ");
                      }
                      fileChannel.close();
                      throw new InCorrectValues("File is being used for reading/writing");
                    } else {
                      byte[] fileData = readFileData(file, fileLength);
                      response.append("HTTP/1.1 200 OK" + "\n");
                      response.append("Server: Java HTTP Server Assignment 2" + "\n");
                      System.out.println(contentDisposition + " Content Disposition");
                      response.append("contentDisposition:" + contentDisposition + "\n");
                      response.append("Date: " + new Date() + "\n");
                      response.append("Content-type: " + content + "\n");
                      response.append(" Connection" + ":" + "close" + "\n");
                      response.append("url :" + "http://localhost:8080/" + file.getName() + "\n");
                      response.append("\n");
                      String fileDatainString = new String(fileData, UTF_8);
                      response.append(fileDatainString);
                      Packet[] packets = getChunks(response.toString().getBytes(),
                          packet);

                      List<Long> packetSent = new ArrayList<>();
                      Set<Long> receivedAck = new HashSet<>();
                      windowSize = getWindowSize(packets.length);
                      myRange = Range.between(0L, new Long(windowSize));
                      buf.clear();
                      for (int i = 0; i <= windowSize; i++) {
                        channel.send(packets[i].toBuffer(), router);
                        stampTimeFor(packets[i]);
                        System.out.println(
                            "Packet Sent" + packets[i]);
                      }
                      while (buf.limit() > 0 && receivedAck.size() != packets.length) {
                        channel.receive(buf);
                        buf.flip();
                        Packet packet1 = Packet.fromBuffer(buf);
                        if (packet1.getType() == 1) {
                          //Add in Set to discard duplicate Ack
                          receivedAck.add(packet1.getSequenceNumber());
                          reTransmitIfRequire(channel, router, packets, receivedAck);
                          if (isSequenceinCurrentWindow(packet1.getSequenceNumber(), myRange)
                              && myRange.getMinimum() + windowSize < packets.length) {
                            myRange = Range
                                .between(myRange.getMinimum() + 1,
                                    myRange.getMaximum() + 1);
                            if (myRange.getMaximum() < packets.length) {
                              channel
                                  .send(packets[Math.toIntExact(myRange.getMaximum())].toBuffer(),
                                      router);
                              packets[Math.toIntExact(myRange.getMaximum())]
                                  .setTimestart(System.currentTimeMillis());
                              System.out.println(
                                  "Packet Sent" + packets[Math.toIntExact(myRange.getMaximum())]);
                            } else {
                              //checking after last packet sent
                              //while (receivedAck.size() != packets.length) {
                              reTransmitIfRequire(channel, router, packets, receivedAck);
                              //}

                              myRange = Range.between(0L, new Long(windowSize));
                              break;
                            }


                          }


                        }
                        buf.flip();

                      }

                    }
                  } finally {
                    if (lock != null) {
                      lock.release();
                    }
                    if (fileChannel != null) {
                      fileChannel.close();
                    }
                  }
                }
              } else {
                try {
                  boolean addResult = receivedPackets.add(packet.getSequenceNumber());
                  File file = new File(WEB_ROOT, requestedFile);
                  int fileLength = (int) file.length();
                  String content = getContentType(requestedFile);
                  System.out.println("POST method called");
                  RandomAccessFile raf = new RandomAccessFile(file.getName(), "rw");
                  fileChannel = raf.getChannel();
                  StringBuilder dataToWrite = new StringBuilder();
                  String[] headerPlusData = payload.split("\r\n\r\n");
                  StringBuilder payLoadData = new StringBuilder();
                  if (headerPlusData.length == 2) {

                    payLoadData.append(new String(headerPlusData[1].getBytes(), UTF_8));
                  } else {

                    payLoadData.append(new String(headerPlusData[0].getBytes(), UTF_8));
                  }
                  StringBuilder response = new StringBuilder();
                  if (file.getAbsoluteFile().exists()) {
                    System.out.println(file.getName() + " Exists");
                    lock = fileChannel.tryLock();
                    if (lock == null) {
                      if (verbose) {
                        System.out.println(
                            file.getName()
                                + "is being used can not perform write operation on it ");
                      }
                      response.append("HTTP/1.1 403 FORBIDDEN" + "\n");
                      response.append("Server: Java HTTP Server Assignment 2" + "\n");
                      response.append("Date: " + new Date() + "\n");
                      response.append("\n");
                      response.append("File is being used for reading/writing");
                      Packet resp = packet.toBuilder()
                          .setPayload(response.toString().getBytes())
                          .create();
                      channel.send(resp.toBuffer(), router);
                      throw new InCorrectValues("File is being used for reading/writing");
                    } else {
                      if (addResult) {
                        writeInFile(file, payLoadData, content, packet, channel, router);
                      } else {
                        System.out
                            .println("Discarding duplicate Packet" + packet.getSequenceNumber());
                      }

                      lock.release();
                    }


                  } else {
                    System.out.println(file.getName() + " Does not exists");
                    if (addResult) {
                      writeInFile(file, payLoadData, content, packet, channel, router);
                    } else {
                      System.out
                          .println("Discarding duplicate Packet" + packet.getSequenceNumber());
                    }
                    if (lock != null) {
                      lock.release();
                    }


                  }

                  response.append("HTTP/1.1 200 OK\n");
                  response.append("Server: Java HTTP Server Assignment 2\n");
                  response.append("Date: " + new Date() + "\n");
                  response.append("Content-type: " + content + "\n");
                  response.append("file:{}");
                  response.append("form:{}");
                  response.append(" Connection" + ":" + "close");
                  response.append("url :" + "http://localhost:8080/" + file.getName());
                  response.append("\n");
                  String data = new String(payLoadData.toString().getBytes(), UTF_8);
                  // response.append("data:" + data);

                  Packet resp = packet.toBuilder()
                      .setType(0)
                      .setPayload(response.toString().getBytes())
                      .create();
                  channel.send(resp.toBuffer(), router);
                } catch (Exception e) {
                  e.printStackTrace();
                } finally {

                }
              }

            }
          }

        } catch (InCorrectValues inCorrectValues1) {
          inCorrectValues1.printStackTrace();
        } catch (FileNotFoundException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          System.out.println("Clearing Received Packet");
          receivedPackets.clear();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }


  }

  private static void copyDefaultFileToRequestedFile(String reQuestedFile, File file) {
    File source = new File(WEB_ROOT, "default.txt");

    try {
      OutputStream os = new FileOutputStream(file);
      Files.copy(Paths.get(source.getName()), os);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void reTransmitIfRequire(DatagramChannel channel, SocketAddress router,
      Packet[] packets, Set<Long> receivedAck) throws IOException {
    Set<Packet> packetsThatNeedRetransmission = isRetrasmissionRequire(
        receivedAck, packets, myRange);

    if (packetsThatNeedRetransmission.size() == 0) {
      //System.out.println("Retransmission not required");
    } else {
      System.out.println(
          "Retransmission starts for ");
      for (Packet p : packetsThatNeedRetransmission) {
        System.out.print("\t" + p.getSequenceNumber() + "\t");
        channel.send(p.toBuffer(), router);
        p.setTimestart(System.currentTimeMillis());
      }
    }
  }

  private static void sendAck(Packet resp,
      DatagramChannel channel, SocketAddress routerAddress) throws IOException {
    System.out.println("Sending Ack starts ");
    System.out.println("Sending ack for the " + resp.getSequenceNumber());
    Packet p = resp.toBuilder()
        .setType(1)
        .setSequenceNumber(resp.getSequenceNumber())
        .setPayload("ack".getBytes())
        .create();

    System.out.println(channel.send(p.toBuffer(), routerAddress));

  }

  private static Set<Packet> isRetrasmissionRequire(Set<Long> receivedAck, Packet[] packets,
      Range<Long> myRange) {
    Set<Packet> packetNeedsToRetrasmit = new HashSet<>();
    for (Packet p : packets) {
      if (p.getTimestart() != 0 && !receivedAck.contains(p.getSequenceNumber()) && myRange
          .contains(p.getSequenceNumber())) {
        long timeDifference = System.currentTimeMillis() - p.getTimestart();
        if (timeDifference > 100L) {
          System.out.println("Ack delayed by " + timeDifference + " ms for " + p);
          packetNeedsToRetrasmit.add(p);
        }
      }
    }
    return packetNeedsToRetrasmit;
  }

  private static void stampTimeFor(Packet p) {
    p.setTimestart(System.currentTimeMillis());
  }

  private static boolean isSequenceinCurrentWindow(long sequenceNumber,
      Range<Long> range) {
    return range.contains(sequenceNumber);
  }


  @Override
  public void run() {
  }

  private String getHeaders(BufferedReader in) throws IOException {
    String headerLine = null;
    String allHeaders = " ";
    while ((headerLine = in.readLine()).length() != 0) {
      System.out.println(headerLine);
      allHeaders += " " + headerLine + "\n";
    }
    return allHeaders.trim();
  }


  private static void writeInFile(File file, StringBuilder payload, String contentType,
      Packet packet, DatagramChannel channel, SocketAddress router)
      throws IOException {

    FileWriter fw = new FileWriter(file, true);
    if (contentType.equalsIgnoreCase("application/json")) {
      String jsonAsString = '{' + payload.toString().replace("&", ",").replace("=", ":") + '}';
//      JSONObject jsonObject = new JSONObject(jsonAsString);
      //    String data = new String(jsonObject.toString().getBytes(), UTF_8);
      fw.write(jsonAsString.replace("null", ""));
      sendAck(packet, channel, router);
    } else {
      /*byte[] fileData = readFileData(file, (int) file.length());
      System.out.println("Exitsing File data " + new String(fileData, UTF_8));*/
      /*payload.append(new String(fileData, UTF_8));*/
      String data = new String(payload.toString().getBytes(), UTF_8);
      fw.write(data);
      sendAck(packet, channel, router);
    }
    fw.close();
  }

  private static byte[] readFileData(File file, int fileLength) throws IOException {
    FileInputStream fileIn = null;
    byte[] fileData = new byte[fileLength];

    try {
      fileIn = new FileInputStream(file);
      fileIn.read(fileData);
    } finally {
      if (fileIn != null) {
        fileIn.close();
      }
    }

    return fileData;
  }

  private static String getContentType(String fileRequested) {
    if (fileRequested.endsWith(".htm") || fileRequested.endsWith(".html")) {
      return "text/html";
    } else if (fileRequested.endsWith(".json")) {
      return "application/json";
    } else if (fileRequested.endsWith(".png")) {
      return "image/png";
    } else if (fileRequested.endsWith(".jpeg")) {
      return "image/jpeg";
    } else {
      return "text/plain";
    }
  }

  private void fileNotFound(PrintWriter outWritter, OutputStream dataOut, String fileRequested)
      throws IOException {
    File file = new File(WEB_ROOT, FILE_NOT_FOUND);
    int fileLength = (int) file.length();
    String content = "text/html";
    byte[] fileData = readFileData(file, fileLength);

    outWritter.println("HTTP/1.1 404 File Not Found");
    outWritter.println("Server: Java HTTP Server Assignment 2");
    outWritter.println("Date: " + new Date());
    outWritter.println("Content-type: " + content);
    outWritter.println("Content-length: " + fileLength);
    outWritter.println();
    outWritter.flush();

    dataOut.write(fileData, 0, fileLength);
    dataOut.flush();

    if (verbose) {
      System.out.println("File " + fileRequested + " not found");
    }
  }

  public static boolean helpCommand(String args[]) throws InCorrectValues {
    if (args.length == 0) {
      throw new InCorrectValues("No Argument passed : type help to see options");
    }
    return args[0].equalsIgnoreCase("help");
  }

  public static Packet[] getChunks(byte[] message, Packet packet) {
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

  public static int getWindowSize(int sequenceNumber) {

    int k = Integer.toBinaryString(sequenceNumber).length();
    System.out.println("Total packets" + sequenceNumber);
    System.out.println(sequenceNumber / 2 - 1);
    return sequenceNumber / 2 - 1;
  }
}

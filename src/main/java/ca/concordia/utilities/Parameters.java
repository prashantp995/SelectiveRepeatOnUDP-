package ca.concordia.utilities;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


public class Parameters {

  String callingMethod;
  boolean verboseOption;
  String url;
  boolean hasHeader;
  JSONObject headers = new JSONObject();
  boolean hasInlineData;
  boolean hasFile;
  boolean hasOutputFile;
  String outPutFileName;
  JSONObject inLinedata = new JSONObject();
  String data;
  String redirectURL;
  public static final List<String> validParams = Arrays.asList("-h", "-v", "-d", "-f", "-o");

  public String getCallingMethod() {
    return callingMethod;
  }

  public boolean isVerboseOption() {
    return verboseOption;
  }

  public String getUrl() {
    return url;
  }

  public boolean isHasHeader() {
    return hasHeader;
  }

  public JSONObject getHeaders() {
    return headers;
  }

  public boolean isHasInlineData() {
    return hasInlineData;
  }

  public boolean isHasFile() {
    return hasFile;
  }


  private void addHeader(String arg) throws InCorrectValues {
    if (isHeaderValid(arg)) {
      String args[] = arg.split(":");
      this.getHeaders().put(args[0], args[1]);
    } else {
      throw new InCorrectValues("header value is not valid for arg" + arg);
    }

  }


  boolean isHeaderValid(String headerValue) {
    String[] keyValue = headerValue.split(":");
    if (keyValue.length > 1) {
      return true;
    } else {
      return false;
    }
  }


  boolean isArgumentsValid(String input[]) throws InCorrectValues {
    String inputString = Arrays.toString(input);
    if (inputString.contains("-d") && inputString.contains("-f")) {
      throw new InCorrectValues("-d and -f together not allowed");
    }
    for (String in : input) {
      if (in.contains("-") && in.length() == 2) {
        if (!validParams.contains(in)) {
          System.out
              .println(in + " is not allowed argument  use help to see list of valid argument");
          return false;
        }
        if (input[input.length - 1].equals("-o")) {
          System.out
              .println(in + " only -o not allowed , please pass output file name");
          return false;
        }
      }
    }
    return true;
  }

  private ArrayList getLink(String input) {
    ArrayList links = new ArrayList();
    String regex;
    if (input.contains("http://")) {
      regex = "\\(?\\b(http://|www[.])[-A-Za-z0-9+&amp;@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&amp;@#/%=~_()|]";
    } else {
      regex = "\\(?\\b(https://|www[.])[-A-Za-z0-9+&amp;@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&amp;@#/%=~_()|]";
    }

    Matcher m = Pattern.compile(regex).matcher(input);
    String urlStr = null;
    while (m.find()) {
      urlStr = m.group();
      if (urlStr.startsWith("(") && urlStr.endsWith(")")) {
        urlStr = urlStr.substring(1, urlStr.length() - 1);
      }
      links.add(urlStr);
    }
    return links;
  }

  public Parameters getParameters(String[] args)
      throws InCorrectValues, IOException, ParseException {
    String test = Arrays.toString(args);
    ArrayList urls = getLink(test);
    if (urls.size() == 0) {
      throw new InCorrectValues("Please provide URL");
    }
    this.url = (String) urls.get(0);
    if (urls.size() > 1) {
      this.redirectURL = (String) urls.get(1);
    }
    boolean isArgumetsValid = isArgumentsValid(args);
    if (isArgumetsValid) {
      int i = 0;
      this.callingMethod =
          isValidCall(args[i]) ? args[i]
              : "invalid call";
      if (!callingMethod.equalsIgnoreCase("invalid call")) {
        i++;
        if (args[i].equals("-v")) {
          this.verboseOption = true;
          i++;
        }
        if (args[i].equals("-h")) {
          this.hasHeader = true;
          i++;
          addHeader(args[i]);
          while (i + 1 < args.length && args[i + 1].equalsIgnoreCase("-h")) {
            i++;
            i++;
            addHeader(args[i]);
          }
          i++;
        }
        if (args[i].equals("-d")) {
          this.hasInlineData = true;
          i++;
          try {
            inLinedata = new JSONObject(
                test.substring(test.indexOf("'") + 1, test.lastIndexOf("'"))
                    .replace(":,", ":"));

          } catch (Exception c) {
            throw new InCorrectValues(
                "Please provide inline data in valid format example '{\"Assignment\": 1}' ");
          }


        }
        if (args[i].equals("-f")) {
          this.hasFile = true;
          i++;
          String path = args[i];
          File file = new File(path);
          byte[] bytes = readFileData(file, (int) file.length());
          this.data = new String(bytes, UTF_8);
          /*JSONParser parser = new JSONParser();
          Object obj = parser.parse(new FileReader(path));
          org.json.simple.JSONObject jsonObject = (org.json.simple.JSONObject) obj;
          inLinedata = new JSONObject(jsonObject.toJSONString());*/
          i++;
        }
        if (args[args.length - 2].equals("-o")) {
          this.hasOutputFile = true;
          this.outPutFileName = args[args.length - 1];
        }

        if (this.callingMethod.equalsIgnoreCase("GET") && (this.hasInlineData || this.hasFile)) {
          throw new InCorrectValues(
              " -f or -d options are not allowed with get operation");
        }
      }
    }
    return this;
  }

  public static boolean isValidCall(String arg) {
    return arg.equalsIgnoreCase("GET") || arg.equalsIgnoreCase("POST");
  }

  public static JSONObject getParametersFromURL(String urlString) throws InCorrectValues {
    JSONObject result = new JSONObject();
    JSONObject params = new JSONObject();
    JSONObject headers = new JSONObject();
    try {
      URL url = new URL(urlString);
      headers.put("Host", url.getHost());
      String parmas[] = url.getQuery().split("&");
      for (String param : parmas) {
        String keyValues[] = param.split("=");
        params.put(keyValues[0], keyValues[1]);
      }
      result.put("args", params);
      result.put("headers", headers);
      result.put("url", urlString);

    } catch (Exception e) {
      e.printStackTrace();
      throw new InCorrectValues("Incorrect Values passed : " + urlString);
    }

    return result;
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
}

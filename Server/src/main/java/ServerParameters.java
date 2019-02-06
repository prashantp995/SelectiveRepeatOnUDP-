package main.java;

import java.util.Arrays;
import java.util.List;

public class ServerParameters {

  boolean verboseOption;
  int port = 8080;
  String dirPath;
  public static final List<String> validParams = Arrays.asList("-d", "-p", "-v");

  public ServerParameters getParameters(String args[]) throws InCorrectValues {
    int i = 0;
    try {
      if (isArgumentsValid(args)) {
        if (args[i].equals("-v")) {
          this.verboseOption = true;
          i++;
        }
        if (args[i].equals("-p")) {
          i++;
          this.port = Integer.parseInt(args[i++]);
        }
        if (args[i].equals("-d")) {
          this.dirPath = args[++i];
        }
      } else {
        System.out.println("Please enter valid argument");
        System.out.println("[-v] [-p PORT] [-d PATH-TO-DIR]\n"
            + "-v Prints debugging messages.\n"
            + "-p Specifies the port number that the server will listen and serve at.\n"
            + "Default is 8080.\n"
            + "-d Specifies the directory that the server will use to read/write");
        throw new InCorrectValues("Please provide valid command");

      }
    } catch (Exception e) {
      throw new InCorrectValues("Please provide valid command");
    }
    return this;
  }

  boolean isArgumentsValid(String input[]) {

    for (String in : input) {
      if (in.contains("-") && in.length() == 2) {
        if (!validParams.contains(in)) {
          System.out
              .println(in + " is not allowed argument  use help to see list of valid argument");
          return false;
        }

      }
    }
    return true;
  }
}

package ca.concordia.utilities;

public class Help {

  public static void printHelp(String arg) {
    switch (arg) {
      case "help":
        printDefaultHelp();
        break;
      case "get":
        printGetHelp();
        break;
      case "post":
        printPostHelp();
        break;
    }
  }

  public static void printGetHelp() {
    System.out.println(
        "usage:  get [-v] [-h key:value] URL \n Get executes a HTTP GET request for a given URL. "
            + "\n -v Prints the detail of the response such as protocol, status, and headers. "
            + "\n -h key:value Associates headers to HTTP Request with the format 'key:value'.");
  }

  public static void printPostHelp() {
    System.out.println("usage:  post [-v] [-h key:value] [-d inline-data] [-f file] URL \n"
        + " Post executes a HTTP POST request for a given URL with inline data or from file."
        + "\n -v Prints the detail of the response such as protocol, status, and headers. "
        + "\n -h key:value Associates headers to HTTP Request with the format 'key:value'. "
        + "\n -d string Associates an inline data to the body HTTP POST request. "
        + "\n -f file Associates the content of a file to the body HTTP POST request. "
        + "\n Either [-d] or [-f] can be used but not both.");
  }

  public static void printDefaultHelp() {
    System.out.println(
        "this  is a curl-like application but supports HTTP protocol only.\nUsage:  command [arguments] The commands are: "
            + "\nget executes a HTTP GET request and prints the response.\npost executes a HTTP POST request and prints the response. \n"
            + "help prints this screen. Use \"httpc help [command]\" for more information about a command.");
  }

  public static boolean helpCommand(String args[]) throws InCorrectValues {
    if (args.length == 0) {
      throw new InCorrectValues("No Argument passed : type help to see options");
    }
    return args[0].equalsIgnoreCase("help");
  }

}

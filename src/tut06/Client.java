package tut06;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Date;

public class Client {
    public static void main(String[] args) {
        final String serverHost = "localhost";

        Socket socketOfClient = null;
        BufferedWriter os = null;
        BufferedReader is = null;
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));
        String s;

        try {

            socketOfClient = new Socket(serverHost, 7777);

            os = new BufferedWriter(new OutputStreamWriter(socketOfClient.getOutputStream()));

            is = new BufferedReader(new InputStreamReader(socketOfClient.getInputStream()));
        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + serverHost);
            return;
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " + serverHost);
            return;
        }

        try {
            String responseLine;
            os.write("Hello! now is " + new Date());
            os.newLine();
            os.flush();
            responseLine = is.readLine();
            System.out.println("Server: " + responseLine);
            os.write("I am a Tom Cat");
            os.newLine();
            os.flush();
            responseLine = is.readLine();
            System.out.println("Server: " + responseLine);

            while (true) {
                System.out.println("Please enter your message");
                s = inFromUser.readLine();
                os.write(s);
                os.newLine();
                os.flush();
                responseLine = is.readLine();
                System.out.println("Server: " + responseLine);
                if (s.equals("QUIT") | (responseLine == null) ) {
                    break;
                }
            }

            os.close();
            is.close();
            socketOfClient.close();
        } catch (UnknownHostException e) {
            System.err.println("Trying to connect to unknown host: " + e);
        } catch (IOException e) {
            System.err.println("IOException: " + e);
        }
    }
}

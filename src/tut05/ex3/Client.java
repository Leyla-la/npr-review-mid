package tut05.ex3;

import java.io.*;
import java.net.*;

public class Client {
    public static void main(String argv[]) throws Exception {
        final String serverHost = "localhost";
        Socket clientSocket = null;
        BufferedWriter outToServer = null;
        BufferedReader inFromServer = null;
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

        try {
            clientSocket = new Socket(serverHost, 6789);

            outToServer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
            inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // Get n from user
            System.out.println("Please enter an integer n:");
            String sentence = inFromUser.readLine();

            // Send to server
            outToServer.write(sentence);
            outToServer.newLine();
            outToServer.flush();

            // Receive from server
            String modifiedSentence = inFromServer.readLine();
            System.out.println("FROM SERVER: Square of n is " + modifiedSentence);

        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + serverHost);
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " + serverHost);
        } finally {
            // Close resources
            try {
                if (outToServer != null) outToServer.close();
                if (inFromServer != null) inFromServer.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
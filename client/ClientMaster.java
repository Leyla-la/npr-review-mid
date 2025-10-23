
/*
 ClientMaster.java
 Master client to interact with ServerMaster.
 - Demonstrates many modes: interactive text, file send, file receive, math mode, command-based.
 - By default uses simple interactive loop. Edit top-of-file constants or uncomment blocks in main to choose automated flows.
 - NOTE: This client intentionally contains helper methods for many cases; in exam, you normally enable only the required block.
*/

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.Scanner;

public class ClientMaster {

    // ==================== PORT SELECTION & SERVER ====================
    // Student ID provided: 4075
    // Choose matching port as in ServerMaster. Make sure both server and client use same active port.
    // ----------------- Edit here -----------------
    // public static final int PORT = 44075; // choose same port as server
    public static final int PORT = 54075;
    // --------------------------------------------
    public static final String HOST = "localhost";

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println("ClientMaster starting. Connect to " + HOST + ":" + PORT);

        try (Socket socket = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
             DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {

            System.out.println("Connected to server. Choose action:");
            System.out.println("1) Interactive text mode (default)");
            System.out.println("2) Send file (PUT)");
            System.out.println("3) Request file (GET)");
            System.out.println("4) Send BigInteger (math)");
            System.out.println("5) Command mode example (SLICE/REPLACE/COUNT)");
            System.out.print("Select (1-5) or press Enter for default: ");

            String sel = sc.nextLine().trim();
            if (sel.isEmpty() || sel.equals("1")) {
                interactiveTextMode(sc, in, out);
            } else if (sel.equals("2")) {
                System.out.print("Enter path to local file to send: ");
                String path = sc.nextLine().trim();
                System.out.print("Enter remote filename to save as (or leave blank): ");
                String remote = sc.nextLine().trim();
                sendPutFile(path, remote.isEmpty() ? new File(path).getName() : remote, out, dataOut, in);
            } else if (sel.equals("3")) {
                System.out.print("Enter filename to GET from server: ");
                String fn = sc.nextLine().trim();
                sendGetFile(fn, out, dataIn);
            } else if (sel.equals("4")) {
                System.out.print("Enter positive integer (Student_ID or other): ");
                String sid = sc.nextLine().trim();
                // Send as simple text and expect server to apply BigInteger logic if server enabled
                out.write(sid);
                out.newLine();
                out.flush();
                String reply = in.readLine();
                System.out.println("Server reply: " + reply);
            } else if (sel.equals("5")) {
                // Example of sending commands to server's commandParser
                System.out.print("Enter a command like: SLICE 2 6 HelloWorld\n> ");
                String cmd = sc.nextLine();
                out.write("CMD " + cmd); out.newLine(); out.flush();
                String r = in.readLine();
                System.out.println("Server: " + r);
            } else {
                System.out.println("Unknown selection. Defaulting to interactive.");
                interactiveTextMode(sc, in, out);
            }

        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + HOST);
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }

    // ==================== INTERACTIVE TEXT MODE ====================
    // Mandatory base: simple loop to send lines and print server replies.
    private static void interactiveTextMode(Scanner sc, BufferedReader in, BufferedWriter out) throws IOException {
        System.out.println("Interactive mode. Type messages. Type QUIT to exit.");
        while (true) {
            System.out.print("You: ");
            String line = sc.nextLine();
            out.write(line);
            out.newLine();
            out.flush();

            if (line.equalsIgnoreCase("QUIT")) break;

            String reply = in.readLine();
            if (reply == null) {
                System.out.println("Server closed connection.");
                break;
            }
            System.out.println("Server: " + reply);
        }
    }

    // ==================== SEND FILE (PUT) ====================
    // Protocol used:
    // 1) Client writes text: "CMD PUT <remoteName>" (or simply "PUT <remoteName>" depending on server)
    // 2) Then client writes long fileLength (DataOutputStream.writeLong) followed by raw bytes
    private static void sendPutFile(String localPath, String remoteName, BufferedWriter out, DataOutputStream dataOut, BufferedReader in) throws IOException {
        File f = new File(localPath);
        if (!f.exists() || !f.isFile()) {
            System.out.println("Local file not found: " + localPath);
            return;
        }
        // Notify server we're sending a PUT command (commandParser expects "PUT filename" if enabled)
        out.write("CMD PUT " + remoteName); out.newLine(); out.flush();
        // Give server small time to prepare if needed (exam servers often do immediate read; this is safe)
        // Now send length + bytes using binary stream
        dataOut.writeLong(f.length());
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                dataOut.write(buffer, 0, read);
            }
            dataOut.flush();
        }
        // Optionally read server confirmation
        String confirm = in.readLine();
        System.out.println("Server confirmation: " + confirm);
    }

    // ==================== GET FILE ====================
    // Protocol used:
    // 1) client sends "CMD GET filename" (or "GET filename")
    // 2) server sends long size then bytes; client stores file as local copy
    private static void sendGetFile(String remoteName, BufferedWriter out, DataInputStream dataIn) throws IOException {
        // send command
        out.write("CMD GET " + remoteName); out.newLine(); out.flush();
        // read size
        long size = dataIn.readLong();
        if (size < 0) {
            System.out.println("Server reported error or invalid size: " + size);
            return;
        }
        String localName = "client_recv_" + System.currentTimeMillis() + "_" + remoteName.replaceAll("[\\\\/]+", "_");
        try (FileOutputStream fos = new FileOutputStream(localName)) {
            byte[] buffer = new byte[4096];
            long remaining = size;
            while (remaining > 0) {
                int read = dataIn.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (read == -1) throw new EOFException("Unexpected EOF during receive");
                fos.write(buffer, 0, read);
                remaining -= read;
            }
            System.out.println("File received and saved as: " + localName + " (" + size + " bytes)");
        }
    }
}

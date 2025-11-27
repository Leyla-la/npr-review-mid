package bigtestnotui;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import java.util.Scanner;

/**
 * Console-only Client (bigtestnotui.Client)
 * - Uses stdin for user input, prints server messages to stdout.
 * - Interactive menu: prompts for host/port/id, supports commands and plain messages.
 */
public class Client {
    private final String defaultHost = "localhost";
    private final int defaultPort = 5000;
    // ------------------ OPTIONAL: auto-connect (comment-controlled) ------------------
    // To enable automatic start (skip host/port/id prompts), set AUTO_CONNECT = true.
    // This is optional for automated testing. When enabled the client will immediately
    // connect using AUTO_HOST/AUTO_PORT/AUTO_ID below.
    // NOTE: toggle this constant only; no other code changes are required.
    private static final boolean AUTO_CONNECT = false; // set to true to auto-connect at startup
    private static final String AUTO_HOST = "localhost";
    private static final int AUTO_PORT = 5000;
    private static final String AUTO_ID = "20520001"; // replace with your preferred default id
    // ----------------------------------------------------------------------------------

    // ------------------ SIMPLE MENU MODE ------------------
    // When true the REPL will present a numbered menu (1..9) to the user for simple operations
    // instead of relying only on slash-commands. This is intended for console-only use.
    private static final boolean SIMPLE_MENU = true;
    // -----------------------------------------------------
    private String host;
    private int port;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final Scanner sc = new Scanner(System.in);
    private String username;

    public Client(String host, int port) { this.host = host; this.port = port; }

    public boolean connect(String id) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.username = id;
            // send ID
            out.println("ID:" + id);
            // read initial responses without blocking forever
            try {
                socket.setSoTimeout(1500);
                String r = in.readLine();
                if (r != null) handleServerLine(r);
            } catch (IOException ignored) {
                // no immediate line, continue
            } finally {
                try { socket.setSoTimeout(0); } catch (Exception ignored) {}
            }
            // start reader thread
            new Thread(() -> {
                try {
                    String l;
                    while ((l = in.readLine()) != null) handleServerLine(l);
                } catch (IOException e) {
                    System.out.println("[CLIENT] Disconnected: " + e.getMessage());
                }
            }).start();
            return true;
        } catch (Exception e) {
            System.out.println("[CLIENT] Connect failed: " + e.getMessage());
            return false;
        }
    }

    private void handleServerLine(String line) {
        if (line == null) return;
        String[] p = line.split(":", 4);
        String prefix = p[0];
        switch (prefix) {
            case "ID_OK": System.out.println("[SYS] ID_OK: " + (p.length>=2? p[1]:"")); break;
            case "ID_RES": System.out.println("[SYS] ID_RES: " + (p.length>=2? p[1]:"")); break;
            case "MSG": // MSG:user:ts:msg
                if (p.length>=4) System.out.println("[MSG] " + p[1] + " @" + p[2] + ": " + p[3]);
                else System.out.println(line);
                break;
            case "SYSTEM": System.out.println("[SYS] " + (p.length>=2? p[1]: line)); break;
            case "FILE_BC": if (p.length>=3) System.out.println("[FILE] " + p[1] + ": " + p[2]); else System.out.println(line); break;
            case "FILE_SEND": if (p.length>=3) {
                try {
                    byte[] data = Base64.getDecoder().decode(p[2]);
                    Path out = Paths.get(p[1]);
                    Files.write(out, data);
                    System.out.println("[FILE] Received file saved to " + out.toAbsolutePath());
                } catch (Exception e) { System.out.println("[FILE] Save failed: " + e.getMessage()); }
            } else System.out.println(line); break;
            default: System.out.println(line); break;
        }
        // print a small prompt so the user knows they can type
        System.out.print("[You] > ");
        System.out.flush();
    }

    private void sendRaw(String s) { if (out!=null) out.println(s); }

    // basic file send using base64 (single-pkt)
    private void sendFile(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) { System.out.println("[FILE] Path not found: " + path); return; }
            String name = p.getFileName().toString();
            byte[] data = Files.readAllBytes(p);
            String b64 = Base64.getEncoder().encodeToString(data);
            out.println("FILEDATA:" + name + ":" + b64);
            System.out.println("[FILE] Sent " + name);
        } catch (Exception e) { System.out.println("[FILE] Send error: " + e.getMessage()); }
    }

    private void printHelp() {
        System.out.println("=== Commands ===");
        System.out.println("/help                Show this help");
        System.out.println("/quit                Quit client");
        System.out.println("/file <absolutePath> Upload file to server (single-pkt base64)");
        System.out.println("/priv <user> <msg>   Send private message to user");
        System.out.println("/list                Request online list");
        System.out.println("/whoami              Request your identity info from server");
        System.out.println("/bank deposit <amt>  Deposit to your bank account");
        System.out.println("/bank withdraw <amt> Withdraw from your bank account");
        System.out.println("/bank balance        Show balance");
        System.out.println("/poll create <title>|<opt1,opt2>  Create poll (options comma-separated)");
        System.out.println("/poll vote <id> <optIndex>        Vote on poll id");
        System.out.println("(Otherwise type a plain message to send to room/server)");
        System.out.println("=================");
    }

    public void repl() {
        System.out.println("Connected. Type /help for commands. Plain text will be sent as message.");
        while (true) {
            if (SIMPLE_MENU) {
                System.out.println("--- Menu: choose an option number or type a plain message ---");
                System.out.println("1) Send message   2) Send file   3) Private message   4) List   5) Whoami");
                System.out.println("6) Bank actions   7) Poll actions  8) Help   9) Quit");
                System.out.print("Choice or message: ");
            } else {
                System.out.print("[You] > ");
            }
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) { System.out.print("[You] > "); continue; }
            // If SIMPLE_MENU enabled and user entered a single digit, handle menu choices
            if (SIMPLE_MENU && line.matches("^[1-9]$")) {
                int choice = Integer.parseInt(line);
                switch (choice) {
                    case 1: // send message
                        System.out.print("Enter message: "); String m = sc.nextLine(); if (m!=null) { sendRaw(m); } break;
                    case 2: // send file
                        System.out.print("Enter absolute file path: "); String fp = sc.nextLine(); if (fp!=null) sendFile(fp.trim()); break;
                    case 3: // private message
                        System.out.print("Target username: "); String tgt = sc.nextLine(); System.out.print("Message: "); String pm = sc.nextLine(); if (tgt!=null && pm!=null) sendRaw("PRIV:" + tgt.trim() + ":" + pm); break;
                    case 4: sendRaw("LIST"); break;
                    case 5: sendRaw("WHOAMI"); break;
                    case 6: // bank submenu
                        System.out.println("Bank: a) deposit  b) withdraw  c) balance"); System.out.print("Choice: "); String bch = sc.nextLine(); if ("a".equalsIgnoreCase(bch)) { System.out.print("Amount: "); String av = sc.nextLine(); sendRaw("BANK:DEPOSIT:" + av.trim()); }
                        else if ("b".equalsIgnoreCase(bch)) { System.out.print("Amount: "); String av2 = sc.nextLine(); sendRaw("BANK:WITHDRAW:" + av2.trim()); }
                        else if ("c".equalsIgnoreCase(bch)) sendRaw("BANK:BALANCE"); break;
                    case 7: // poll submenu
                        System.out.println("Poll: a) create  b) vote"); System.out.print("Choice: "); String pch = sc.nextLine(); if ("a".equalsIgnoreCase(pch)) { System.out.print("Title: "); String title = sc.nextLine(); System.out.print("Options (comma): "); String opts = sc.nextLine(); sendRaw("POLL:CREATE:" + title + ":" + opts); }
                        else if ("b".equalsIgnoreCase(pch)) { System.out.print("Poll id: "); String pid = sc.nextLine(); System.out.print("Option index: "); String idx = sc.nextLine(); sendRaw("POLL:VOTE:" + pid.trim() + ":" + idx.trim()); } break;
                    case 8: printHelp(); break;
                    case 9: sendRaw("QUIT"); System.out.println("Quitting..."); return;
                }
                continue; // loop
            }

            // Non-menu or slash-command handling falls back to previous command syntax
            if (line.equalsIgnoreCase("/quit")) { sendRaw("QUIT"); break; }
            if (line.equalsIgnoreCase("/help")) { printHelp(); continue; }
            if (line.startsWith("/file ")) { String p = line.substring(6).trim(); sendFile(p); continue; }
            if (line.startsWith("/priv ")) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) { System.out.println("Usage: /priv <user> <msg>"); continue; }
                String target = parts[1]; String msg = parts[2]; sendRaw("PRIV:" + target + ":" + msg); continue;
            }
             if (line.equalsIgnoreCase("/list")) { sendRaw("LIST"); System.out.print("[You] > "); continue; }
             if (line.equalsIgnoreCase("/whoami")) { sendRaw("WHOAMI"); System.out.print("[You] > "); continue; }
             if (line.startsWith("/bank ")) {
                 String[] p = line.split("\\s+",3);
                 if (p.length<2) { System.out.println("/bank deposit|withdraw|balance"); System.out.print("[You] > "); continue; }
                 String act = p[1].toUpperCase(Locale.ROOT);
                 if ("DEPOSIT".equalsIgnoreCase(act) || "WITHDRAW".equalsIgnoreCase(act)) {
                     if (p.length<3) { System.out.println("Usage: /bank " + act.toLowerCase() + " <amount>"); System.out.print("[You] > "); continue; }
                     sendRaw("BANK:" + act + ":" + p[2]);
                 } else if ("BALANCE".equalsIgnoreCase(act)) sendRaw("BANK:BALANCE"); else System.out.println("Unknown /bank action");
                 System.out.print("[You] > "); continue;
             }
             if (line.startsWith("/poll ")) {
                 String[] parts = line.split("\\s+",3);
                 if (parts.length<2) { System.out.println("/poll create|vote ..."); System.out.print("[You] > "); continue; }
                 String act = parts[1].toLowerCase(Locale.ROOT);
                 if ("create".equals(act)) {
                     if (parts.length<3) { System.out.println("Usage: /poll create <title>|<opt1,opt2>"); System.out.print("[You] > "); continue; }
                     String[] t = parts[2].split("\\|",2);
                     if (t.length<2) { System.out.println("Provide title and options separated by '|'"); System.out.print("[You] > "); continue; }
                     String title = t[0].trim(); String opts = t[1].trim(); sendRaw("POLL:CREATE:" + title + ":" + opts); System.out.print("[You] > "); continue;
                 } else if ("vote".equals(act)) {
                     if (parts.length<3) { System.out.println("Usage: /poll vote <id> <optIndex>"); System.out.print("[You] > "); continue; }
                     String[] t = parts[2].split("\\s+",2);
                     if (t.length<2) { System.out.println("Usage: /poll vote <id> <optIndex>"); System.out.print("[You] > "); continue; }
                     sendRaw("POLL:VOTE:" + t[0] + ":" + t[1]); System.out.print("[You] > "); continue;
                 } else { System.out.println("Unknown /poll action"); System.out.print("[You] > "); continue; }
             }

             // default: send as plain text
             sendRaw(line);
            System.out.print("[You] > ");
         }
         try { socket.close(); } catch (IOException ignored) {}
         System.out.println("Client exiting");
     }

     public static void main(String[] args) {
         // If you prefer auto-connect (useful for tests), set AUTO_CONNECT=true at top of file.
         String host; int port;
         String id;
         if (AUTO_CONNECT) {
             host = AUTO_HOST; port = AUTO_PORT; id = AUTO_ID;
             System.out.println("AUTO_CONNECT enabled: host=" + host + " port=" + port + " id=" + id);
         } else {
             Scanner s = new Scanner(System.in);
             System.out.print("Server host [enter for localhost]: ");
             host = s.nextLine().trim(); if (host.isEmpty()) host = "localhost";
             System.out.print("Server port [enter for 5000]: ");
             String ps = s.nextLine().trim(); port = 5000; if (!ps.isEmpty()) { try { port = Integer.parseInt(ps); } catch (Exception ignored) {} }
             System.out.print("Enter your student id or username: ");
             id = s.nextLine().trim(); if (id.isEmpty()) { System.out.println("No id provided, using 'anon' prefix"); id = "anon" + (int)(Math.random()*1000); }
         }

         Client c = new Client(host, port);
         if (!c.connect(id)) return;
         c.repl();
     }
}

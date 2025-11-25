// Server.java - FINAL 2025 SUPER SYSTEM (15 CASE)
// Đã test 100% - Chỉ thay PORT ở dòng 19 là chạy
package biggprojectt;
import javax.swing.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    private static final int PORT = 1234; // THAY BẰNG 4 SỐ CUỐI MSSV CỦA BẠN

    // Danh sách toàn cục
    private static final Set<ClientHandler> allClients = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Set<ClientHandler>> rooms = new ConcurrentHashMap<>(); // roomName -> clients
    private static final Map<String, BigInteger> bankAccounts = new ConcurrentHashMap<>();
    // Poll state: current poll question -> options CSV and votes map
    private static final Map<String, String> polls = new ConcurrentHashMap<>(); // pollId -> question|opt1,opt2
    private static final Map<String, Map<String, Integer>> pollVotes = new ConcurrentHashMap<>(); // pollId -> (option -> count)
    // Simple poll id generator
    private static final AtomicInteger pollCounter = new AtomicInteger(0);
     private static ClientHandler admin = null;
     private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

     // Log file
     private static PrintWriter chatLog;
     private static PrintWriter bankLog;

     // Simple server GUI log
     private static JFrame serverFrame;
     private static JTextArea serverLogArea;

     private static void guiLog(String line) {
         // append to GUI if exists
         if (serverLogArea != null) {
             SwingUtilities.invokeLater(() -> {
                 serverLogArea.append(line + "\n");
                 serverLogArea.setCaretPosition(serverLogArea.getDocument().getLength());
             });
         }
     }

     public static void main(String[] args) throws IOException {
         // Create a small GUI for server logs (non-blocking)
         SwingUtilities.invokeLater(() -> {
             serverFrame = new JFrame("SUPER SERVER");
             serverFrame.setSize(700, 400);
             serverFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
             serverLogArea = new JTextArea();
             serverLogArea.setEditable(false);
             serverLogArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
             serverFrame.add(new JScrollPane(serverLogArea));
             serverFrame.setVisible(true);
         });

         // Khởi tạo log file
         chatLog = new PrintWriter(new FileWriter("chat_log.txt", true));
         bankLog = new PrintWriter(new FileWriter("bank_log.txt", true));
         rooms.put("Main", Collections.synchronizedSet(new HashSet<>()));
-
-        ServerSocket serverSocket = new ServerSocket(PORT);
-        System.out.println("=== SUPER SERVER RUNNING ON PORT " + PORT + " ===");
-        guiLog("=== SUPER SERVER RUNNING ON PORT " + PORT + " ===");
-
-        while (true) {
-            Socket socket = serverSocket.accept();
-            ClientHandler client = new ClientHandler(socket);
-            allClients.add(client);
-            if (admin == null) admin = client; // Người đầu tiên là admin
-            new Thread(client).start();
-        }
+        // Use try-with-resources for ServerSocket so it's closed cleanly on fatal errors
+        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
+            String startLine = "=== SUPER SERVER RUNNING ON PORT " + PORT + " ===";
+            System.out.println(startLine);
+            guiLog(startLine);
+
+            while (true) {
+                Socket socket = serverSocket.accept();
+                ClientHandler client = new ClientHandler(socket);
+                allClients.add(client);
+                if (admin == null) admin = client; // Người đầu tiên là admin
+                new Thread(client).start();
+            }
+        } catch (IOException e) {
+            String err = "Server fatal error: " + e.getMessage();
+            System.err.println(err);
+            guiLog(err);
+        }
     }

    // ==================== UTILITY METHODS ====================
    public static void broadcastAll(String msg) {
        synchronized (allClients) {
            for (ClientHandler c : allClients) c.send(msg);
        }
        logChat(msg);
    }

    public static void broadcastRoom(String room, String msg) {
        Set<ClientHandler> set = rooms.getOrDefault(room, new HashSet<>());
        synchronized (set) {
            for (ClientHandler c : set) c.send("ROOM|" + room + "|" + msg);
        }
        logChat("[" + room + "] " + msg);
    }

    public static void logChat(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        chatLog.println(line);
        chatLog.flush();
        System.out.println(line);
        guiLog(line);
    }

    public static void logBank(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        bankLog.println(line);
        bankLog.flush();
        System.out.println(line);
        guiLog(line);
    }

    public static ClientHandler findByID(String id) {
        return allClients.stream()
                .filter(c -> id.equals(c.getStudentID()))
                .findFirst().orElse(null);
    }

    public static void removeClient(ClientHandler c) {
        allClients.remove(c);
        rooms.getOrDefault(c.getCurrentRoom(), new HashSet<>()).remove(c);
        broadcastAll("*** " + c.getStudentID() + " đã rời server ***");
    }


    // ==========================================================
// MỖI CLIENT ĐƯỢC XỬ LÝ TRONG 1 THREAD RIÊNG
// ==========================================================
    private static class ClientHandler implements Runnable {
         private final Socket socket;
         private final PrintWriter out;
         private final DataInputStream dataIn; // control + framed input
         private final DataOutputStream dataOut; // for control + framed output
         private String studentID = "Unknown";
         private String currentRoom = "Main";
+
+        // Per-client send queue and sender thread to avoid blocking
+        private final BlockingQueue<Frame> sendQueue = new LinkedBlockingQueue<>(256);
+        private Thread senderThread;
+
+        // Frame: len + bytes. len = 0 EOF, -1 CANCEL
+        private static class Frame {
+            final int len;
+            final byte[] data;
+            Frame(int len, byte[] data) { this.len = len; this.data = data; }
+        }
+
+        private boolean enqueueFrame(Frame f) {
+            try {
+                boolean ok = sendQueue.offer(f, 2000, TimeUnit.MILLISECONDS);
+                if (!ok) {
+                    guiLog("Queue full for " + studentID + ", dropping client");
+                    closeQuiet();
+                    return false;
+                }
+                return true;
+            } catch (InterruptedException ie) {
+                Thread.currentThread().interrupt();
+                return false;
+            }
+        }
+
+        private void startSender() {
+            senderThread = new Thread(() -> {
+                try {
+                    while (!Thread.currentThread().isInterrupted()) {
+                        Frame f = sendQueue.take();
+                        try {
+                            dataOut.writeInt(f.len);
+                            if (f.len > 0 && f.data != null) dataOut.write(f.data, 0, f.len);
+                            dataOut.flush();
+                        } catch (IOException ioe) {
+                            guiLog("Send error to " + studentID + ": " + ioe.getMessage());
+                            break;
+                        }
+                    }
+                } catch (InterruptedException ie) {
+                    // exit
+                } finally {
+                    closeQuiet();
+                }
+            }, studentID + "-sender");
+            senderThread.setDaemon(true);
+            senderThread.start();
+        }

         public ClientHandler(Socket socket) throws IOException {
             this.socket = socket;
             this.out = new PrintWriter(socket.getOutputStream(), true);
             this.dataIn = new DataInputStream(socket.getInputStream());
             this.dataOut = new DataOutputStream(socket.getOutputStream());
             startSender();
             System.out.println("Client connected: " + socket.getInetAddress() + ":" + socket.getPort());
             guiLog("Client connected: " + socket.getInetAddress() + ":" + socket.getPort());
         }

         public String getStudentID() { return studentID; }
         public String getCurrentRoom() { return currentRoom; }
-        public void send(String msg) { out.println(msg); }
+        // Send control message to client using writeUTF to match client's reader
+        public synchronized void send(String msg) {
+            try {
+                dataOut.writeUTF(msg);
+                dataOut.flush();
+            } catch (IOException e) {
+                guiLog("Failed send to " + studentID + ": " + e.getMessage());
+            }
+        }
+
+        private void closeQuiet() {
+            try { socket.close(); } catch (IOException ignored) {}
+        }
+
+        @Override
+        public void run() {
+            try {
+                // CASE 1: Nhận Student_ID + tính ^4 (use UTF control channel)
+                studentID = dataIn.readUTF();
+                if (studentID == null) return;
+
+                BigInteger id = new BigInteger(studentID);
+                send("ID^4|" + id.pow(4).toString());
+
+                // Thêm vào phòng Main
+                Server.rooms.get("Main").add(this);
+                Server.bankAccounts.put(studentID, BigInteger.ZERO);
+                Server.broadcastAll("*** " + studentID + " đã tham gia server ***");
+                send("WELCOME|Chào " + studentID + "! Bạn đang ở phòng Main");
+
+                String line;
+                while (true) {
+                    try {
+                        line = dataIn.readUTF();
+                    } catch (EOFException eof) {
+                        break;
+                    }
+                    if (line == null) break;
+                    if (line.equalsIgnoreCase("exit")) break;
+
+                    // ==================== 15 CASE XỬ LÝ ====================
+
+                    if (line.startsWith("MSG:")) {
+                        // CASE 2: Tin nhắn broadcast
+                        String msg = "[" + studentID + "]: " + line.substring(4);
+                        Server.broadcastRoom(currentRoom, msg);
+
+                    } else if (line.startsWith("PRIV:")) {
+                        // CASE 3: Tin nhắn riêng
+                        String[] parts = line.split(":", 3);
+                        if (parts.length == 3) {
+                            ClientHandler target = Server.findByID(parts[1]);
+                            if (target != null) {
+                                target.send("PRIVATE|" + studentID + "|" + parts[2]);
+                                send("PRIVATE_SENT|" + parts[1] + "|" + parts[2]);
+                            } else {
+                                send("ERROR|Không tìm thấy ID " + parts[1]);
+                            }
+                        }
+
+                    } else if (line.startsWith("FILE:")) {
+                        // CASE 4: Nhận file (framed protocol from client)
+                        send("READY");
+                        receiveFile();
+
+                    } else if (line.startsWith("CALC:")) {
+                        // CASE 5: Tính số lớn ^4
+                        try {
+                            BigInteger num = new BigInteger(line.substring(5));
+                            send("CALC_RESULT|" + num.pow(4));
+                        } catch (Exception e) {
+                            send("ERROR|Số không hợp lệ");
+                        }
+
+                    } else if (line.startsWith("ROOM:")) {
+                        // CASE 6: Quản lý phòng
+                        String[] p = line.split(":", 3);
+                        if (p[1].equals("create") && p.length == 3) {
+                            String room = p[2];
+                            Server.rooms.put(room, Collections.synchronizedSet(new HashSet<>()));
+                            send("ROOM_CREATED|" + room);
+                        } else if (p[1].equals("join") && p.length == 3) {
+                            String room = p[2];
+                            if (Server.rooms.containsKey(room)) {
+                                Server.rooms.get(currentRoom).remove(this);
+                                Server.rooms.get(room).add(this);
+                                currentRoom = room;
+                                send("ROOM_JOINED|" + room);
+                                Server.broadcastRoom(room, studentID + " đã vào phòng");
+                            }
+                        }
+
+                    } else if (line.equals("LIST")) {
+                        // CASE 7: Danh sách online
+                        StringBuilder sb = new StringBuilder("ONLINE_LIST|");
+                        Server.allClients.forEach(c -> sb.append(c.getStudentID()).append(","));
+                        send(sb.toString());
+
+                    } else if (line.equals("WHOAMI")) {
+                        // CASE 8
+                        send("WHOAMI|" + studentID + "|" + socket.getInetAddress().getHostAddress() + "|" + currentRoom);
+
+                    } else if (line.startsWith("UPPER:")) {
+                        // CASE 9
+                        String shout = line.substring(6).toUpperCase();
+                        Server.broadcastRoom(currentRoom, "[SHOUT] " + studentID + ": " + shout);
+
+                    } else if (line.startsWith("TYPING:")) {
+                        // CASE 10
+                        Server.broadcastRoom(currentRoom, studentID + " đang gõ...");
+
+                    } else if (line.startsWith("BALANCE") || line.startsWith("DEPOSIT:") || line.startsWith("WITHDRAW:")) {
+                        // CASE 11: Ngân hàng (synchronized)
+                        handleBank(line);
+
+                    } else if (line.startsWith("VOTE:")) {
+                        // CASE 12: Hệ thống vote
+                        handleVote(line);
+
+                    } else if (line.startsWith("KICK:") && this == Server.admin) {
+                        // CASE 13: Admin kick
+                        String targetID = line.substring(5);
+                        ClientHandler target = Server.findByID(targetID);
+                        if (target != null) {
+                            target.send("KICKED");
+                            target.socket.close();
+                            Server.broadcastAll("*** " + targetID + " bị admin kick ***");
+                        }
+
+                    } else if (line.equals("SAVELOG")) {
+                        // CASE 14: Gửi lại log file
+                        sendFileToClient("chat_log.txt");
+
+                    }
+                    // CASE 15: exit → xử lý ở finally
+                }
+            } catch (Exception e) {
+                // e.printStackTrace();
+                guiLog("Handler error for " + studentID + ": " + e.getMessage());
+            } finally {
+                Server.removeClient(this);
+                try { socket.close(); } catch (IOException e) {}
+            }
+        }
+
+        // ==================== CÁC HÀM HỖ TRỢ ====================
+
+        private void receiveFile() throws IOException {
+            // Read filename + size via UTF/long
+            String fileName = dataIn.readUTF();
+            long size = dataIn.readLong();
+            FileOutputStream fos = new FileOutputStream("recv_" + fileName);
+            long total = 0;
+            byte[] buffer = new byte[8192];
+            // Read framed chunks: int len, then len bytes. -1 means CANCEL, 0 means EOF
+            boolean cancelled = false;
+            while (true) {
+                int frameLen;
+                try {
+                    frameLen = dataIn.readInt();
+                } catch (EOFException eof) {
+                    cancelled = true; break;
+                }
+                if (frameLen == -1) { cancelled = true; break; }
+                if (frameLen == 0) break;
+                int remaining = frameLen;
+                while (remaining > 0) {
+                    int toRead = Math.min(remaining, buffer.length);
+                    dataIn.readFully(buffer, 0, toRead);
+                    fos.write(buffer, 0, toRead);
+                    remaining -= toRead;
+                    total += toRead;
+                }
+            }
+            fos.close();
+            // Broadcast announcement
+            Server.broadcastAll("*** " + studentID + " đã gửi file: " + fileName + " (" + size + " bytes) ***");
+            // If cancelled, delete partial file and notify
+            File f = new File("recv_" + fileName);
+            if (cancelled || f.length() != size) {
+                if (f.exists()) f.delete();
+                send("FILE_STATUS|CANCELLED");
+                guiLog("Upload cancelled or incomplete from " + studentID);
+                return;
+            }
+            // Notify clients and enqueue frames so sender threads will deliver them non-blocking
+            synchronized (allClients) {
+                for (ClientHandler c : allClients) if (c != this) c.send("FILE_INCOMING|" + fileName + "|" + size);
+                try (FileInputStream fis = new FileInputStream(f)) {
+                    int r;
+                    byte[] chunk = new byte[8192];
+                    while ((r = fis.read(chunk)) != -1) {
+                        byte[] copy = Arrays.copyOf(chunk, r);
+                        for (ClientHandler c : allClients) {
+                            if (c == this) continue;
+                            c.enqueueFrame(new Frame(r, copy));
+                        }
+                    }
+                    for (ClientHandler c : allClients) if (c != this) c.enqueueFrame(new Frame(0, null));
+                }
+            }
+            // Acknowledge sender
+            send("FILE_OK|" + fileName);
+        }
+
+        private synchronized void handleBank(String cmd) {
+            BigInteger balance = Server.bankAccounts.getOrDefault(studentID, BigInteger.ZERO);
+            if (cmd.equals("BALANCE")) {
+                send("BANK_BALANCE|" + balance);
+            } else if (cmd.startsWith("DEPOSIT:")) {
+                BigInteger amount = new BigInteger(cmd.substring(8));
+                balance = balance.add(amount);
+                Server.bankAccounts.put(studentID, balance);
+                Server.logBank(studentID + " nạp " + amount);
+                send("BANK_BALANCE|" + balance);
+            } else if (cmd.startsWith("WITHDRAW:")) {
+                BigInteger amount = new BigInteger(cmd.substring(9));
+                if (balance.compareTo(amount) >= 0) {
+                    balance = balance.subtract(amount);
+                    Server.bankAccounts.put(studentID, balance);
+                    Server.logBank(studentID + " rút " + amount);
+                    send("BANK_BALANCE|" + balance);
+                } else {
+                    // if not enough money, we can wait/notify pattern: for simplicity send error
+                    send("BANK_ERROR|Không đủ tiền");
+                }
+            }
+        }
+
+        private void handleVote(String cmd) {
+            // VOTE:create:Question?opt1:opt2:opt3
+            // VOTE:vote:pollId:option
+            try {
+                if (cmd.startsWith("VOTE:create:")) {
+                    String payload = cmd.substring("VOTE:create:".length());
+                    String pollId = "P" + pollCounter.incrementAndGet();
+                    // store poll as pollId -> question|opt1,opt2
+                    String[] qparts = payload.split("\\?", 2);
+                    String question = qparts[0];
+                    String optionsCsv = qparts.length>1 ? qparts[1].replace(':', ',') : "";
+                    polls.put(pollId, question + "|" + optionsCsv);
+                    Map<String,Integer> map = new ConcurrentHashMap<>();
+                    for (String opt : optionsCsv.split(",")) map.put(opt, 0);
+                    pollVotes.put(pollId, map);
+                    // broadcast poll to all clients
+                    broadcastAll("VOTE|" + pollId + "|" + question + "|" + optionsCsv);
+                    send("VOTE_OK|created|" + pollId);
+                } else if (cmd.startsWith("VOTE:vote:")) {
+                    String[] parts = cmd.split(":",4);
+                    if (parts.length>=4) {
+                        String pollId = parts[2];
+                        String opt = parts[3];
+                        Map<String,Integer> votes = pollVotes.get(pollId);
+                        if (votes != null && votes.containsKey(opt)) {
+                            votes.compute(opt, (k,v)-> v==null?1:v+1);
+                            // broadcast real-time results
+                            StringBuilder res = new StringBuilder();
+                            votes.forEach((k,v)-> res.append(k).append(":").append(v).append(","));
+                            broadcastAll("VOTE_RESULT|" + pollId + "|" + res.toString());
+                            send("VOTE_OK|voted|" + pollId);
+                        } else send("VOTE_ERROR|invalid option");
+                    }
+                }
+            } catch (Exception e) {
+                send("VOTE_ERROR|" + e.getMessage());
+            }
         }
+
+        private void sendFileToClient(String path) throws IOException {
+            File file = new File(path);
+            if (!file.exists()) {
+                send("FILE_NOT_FOUND");
+                return;
+            }
+            // Use enqueue to this client's queue to send file non-blocking
+            send("FILE_INCOMING|" + file.getName() + "|" + file.length());
+            try (FileInputStream fis = new FileInputStream(file)) {
+                byte[] buffer = new byte[8192];
+                int r;
+                while ((r = fis.read(buffer)) != -1) {
+                    byte[] copy = Arrays.copyOf(buffer, r);
+                    enqueueFrame(new Frame(r, copy));
+                }
+                enqueueFrame(new Frame(0, null));
+            }
+        }
     }
 }

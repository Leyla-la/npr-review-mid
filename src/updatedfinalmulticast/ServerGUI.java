package updatedfinalmulticast;


import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 ServerGUI (Broadcast Mode)
 - Đây là lớp GUI + TCP server xử lý broadcast
 - Trách nhiệm:
   * Hiển thị giao diện quản lý server (start/stop, log, danh sách client)
   * Lắng nghe kết nối client, tạo ClientHandler cho mỗi client
   * Nhận file/tin nhắn từ client và broadcast tới các client khác
 - Ghi chú về cấu trúc:
   * Mỗi client có một ClientHandler chạy trên thread riêng
   * File upload được xử lý theo framed streaming protocol (int len + bytes). Server không giữ toàn bộ file trong RAM.
 */
public class ServerGUI extends JFrame {
    private static final Logger LOGGER = Logger.getLogger(ServerGUI.class.getName());
    private static final int PORT = 4075;
    private static final String UPLOAD_DIR = "server_files/";

    // --- Operational limits / timeouts ---
    // Maximum allowed file size for upload (bytes). Adjust as required.
    private static final long MAX_FILE_SIZE = 500L * 1024L * 1024L; // 500 MB
    // Socket read timeout while waiting for upload frames (ms)
    private static final int UPLOAD_READ_TIMEOUT_MS = 30_000; // 30 seconds
    // Per-client send queue capacity (number of frames)
    private static final int PER_CLIENT_QUEUE_CAPACITY = 256;
    // Timeout when offering a frame into client's queue (ms)
    private static final long QUEUE_OFFER_TIMEOUT_MS = 2000;

     private JTextArea txtLog;
     private JButton btnStart;
     private JButton btnStop;
     private JLabel lblStatus;
     private JLabel lblClientCount;
     private DefaultListModel<String> clientListModel;
     private JList<String> clientList;

     private ServerSocket serverSocket;
     private boolean isRunning = false;
     private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
     private int clientCounter = 0;

     public ServerGUI() {
         initializeGUI();
         // Ensure upload directory exists. Log a stderr warning if creation fails.
         File d = new File(UPLOAD_DIR);
         if (!d.exists()) {
             boolean created = d.mkdirs();
             if (!created) System.err.println("Warning: cannot create upload directory: " + UPLOAD_DIR);
         }
     }

     private void initializeGUI() {
         setTitle("TCP Server - Broadcast Mode");
         setSize(900, 600);
         setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
         setLocationRelativeTo(null);

         JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
         mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

         // Top panel - Control
         mainPanel.add(createControlPanel(), BorderLayout.NORTH);

         // Center panel - Split (Log + Client List)
         JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
         splitPane.setLeftComponent(createLogPanel());
         splitPane.setRightComponent(createClientListPanel());
         splitPane.setDividerLocation(600);
         mainPanel.add(splitPane, BorderLayout.CENTER);

         add(mainPanel);

         addWindowListener(new java.awt.event.WindowAdapter() {
             @Override
             public void windowClosing(java.awt.event.WindowEvent e) {
                 stopServer();
             }
         });
     }

     private JPanel createControlPanel() {
         JPanel panel = new JPanel(new GridBagLayout());
         panel.setBorder(BorderFactory.createTitledBorder("Server Control"));
         GridBagConstraints gbc = new GridBagConstraints();
         gbc.insets = new Insets(5, 5, 5, 5);
         gbc.fill = GridBagConstraints.HORIZONTAL;

         gbc.gridx = 0; gbc.gridy = 0;
         btnStart = new JButton("Start Server");
         btnStart.addActionListener(e -> startServer());
         panel.add(btnStart, gbc);

         gbc.gridx = 1;
         btnStop = new JButton("Stop Server");
         btnStop.setEnabled(false);
         btnStop.addActionListener(e -> stopServer());
         panel.add(btnStop, gbc);

         gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
         lblStatus = new JLabel("Status: Stopped");
         lblStatus.setForeground(Color.RED);
         lblStatus.setFont(new Font("Arial", Font.BOLD, 14));
         panel.add(lblStatus, gbc);

         gbc.gridy = 2;
         lblClientCount = new JLabel("Connected Clients: 0");
         lblClientCount.setFont(new Font("Arial", Font.PLAIN, 12));
         panel.add(lblClientCount, gbc);

         return panel;
     }

     private JPanel createLogPanel() {
         JPanel panel = new JPanel(new BorderLayout());
         panel.setBorder(BorderFactory.createTitledBorder("Server Log"));

         txtLog = new JTextArea();
         txtLog.setEditable(false);
         // Use Times New Roman for server logs per user request
         txtLog.setFont(new Font("Times New Roman", Font.PLAIN, 12));
         JScrollPane scrollPane = new JScrollPane(txtLog);
         panel.add(scrollPane, BorderLayout.CENTER);

         return panel;
     }

     private JPanel createClientListPanel() {
         JPanel panel = new JPanel(new BorderLayout());
         panel.setBorder(BorderFactory.createTitledBorder("Connected Clients"));

         clientListModel = new DefaultListModel<>();
         clientList = new JList<>(clientListModel);
         clientList.setFont(new Font("Consolas", Font.PLAIN, 11));
         JScrollPane scrollPane = new JScrollPane(clientList);
         panel.add(scrollPane, BorderLayout.CENTER);

         return panel;
     }

     private void startServer() {
         try {
             serverSocket = new ServerSocket(PORT);
             serverSocket.setReuseAddress(true);
             isRunning = true;

             btnStart.setEnabled(false);
             btnStop.setEnabled(true);
             lblStatus.setText("Status: Running on port " + PORT);
             lblStatus.setForeground(new Color(0, 150, 0));

             log("=== SERVER STARTED ===");
             log("Port: " + PORT);
             log("Upload directory: " + UPLOAD_DIR);
             log("Waiting for clients...\n");

             // Accept clients trong thread riêng
             new Thread(() -> {
                 while (isRunning) {
                     try {
                         Socket clientSocket = serverSocket.accept();
                         String clientId = "Client #" + (++clientCounter);
                         String clientInfo = clientSocket.getInetAddress().getHostAddress() + ":" +
                                 clientSocket.getPort();

                         ClientHandler handler = new ClientHandler(clientSocket, clientId, clientInfo);
                         clients.add(handler);
                         new Thread(handler).start();

                         log("✓ " + clientId + " [" + clientInfo + "] connected");
                         updateClientList();

                     } catch (IOException e) {
                         if (isRunning) {
                             log("✗ Error accepting client: " + e.getMessage());
                         }
                     }
                 }
             }).start();

         } catch (IOException e) {
             log("✗ Server start error: " + e.getMessage());
             JOptionPane.showMessageDialog(this,
                     "Cannot start server!\n" + e.getMessage(),
                     "Error", JOptionPane.ERROR_MESSAGE);
         }
     }

     private void stopServer() {
         isRunning = false;

         try {
             // Đóng tất cả client connections
             synchronized (clients) {
                 for (ClientHandler client : clients) {
                     client.close();
                 }
                 clients.clear();
             }

             if (serverSocket != null && !serverSocket.isClosed()) {
                 serverSocket.close();
             }

             btnStart.setEnabled(true);
             btnStop.setEnabled(false);
             lblStatus.setText("Status: Stopped");
             lblStatus.setForeground(Color.RED);

             log("\n=== SERVER STOPPED ===\n");
             updateClientList();

         } catch (IOException e) {
             log("✗ Error stopping server: " + e.getMessage());
         }
     }

     private void broadcast(String message, ClientHandler sender) {
         synchronized (clients) {
             for (ClientHandler client : clients) {
                 if (client != sender) { // Không gửi lại cho người gửi
                     client.sendMessage(message);
                 }
             }
         }
     }

     private void broadcastFile(String fileName, long fileSize, byte[] fileData, ClientHandler sender) {
         synchronized (clients) {
             for (ClientHandler client : clients) {
                 if (client != sender) {
                     client.sendFile(fileName, fileSize, fileData);
                 }
             }
         }
     }

     private void updateClientList() {
         SwingUtilities.invokeLater(() -> {
             clientListModel.clear();
             synchronized (clients) {
                 for (ClientHandler client : clients) {
                     clientListModel.addElement(client.getDisplayInfo());
                 }
             }
             lblClientCount.setText("Connected Clients: " + clients.size());
         });
     }

     private void log(String message) {
         SwingUtilities.invokeLater(() -> {
             txtLog.append(message + "\n");
             txtLog.setCaretPosition(txtLog.getDocument().getLength());
         });
     }

     // Inner class ClientHandler
     private class ClientHandler implements Runnable {
         private Socket socket;
         private String clientId;
         private String clientInfo;
         private String studentId;
         private BufferedReader reader;
         private BufferedWriter writer;
         private DataInputStream dataIn;
         private DataOutputStream dataOut;

         // Per-client send queue to avoid blocking the uploader when some clients are slow.
         // Frames are small chunks (len + bytes) that will be written to client's dataOut by senderThread.
         private final BlockingQueue<Frame> sendQueue = new LinkedBlockingQueue<>(PER_CLIENT_QUEUE_CAPACITY);
         private Thread senderThread;

         // Frame represents a single frame to be sent to the client
         private class Frame {
             final int len; // -1 = CANCEL, 0 = EOF, >0 = bytes length
             final byte[] data; // null for control frames
             Frame(int len, byte[] data) { this.len = len; this.data = data; }
         }

         // Enqueue a frame to this client's send queue. If queue is full / client too slow,
         // we will log and drop the client for robustness.
         private boolean enqueueFrame(Frame f) {
             try {
                 boolean ok = sendQueue.offer(f, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                 if (!ok) {
                     log("  Warning: send queue full for " + clientId + " — dropping client");
                     // best-effort close
                     close();
                     return false;
                 }
                 return true;
             } catch (InterruptedException ie) {
                 Thread.currentThread().interrupt();
                 return false;
             }
         }

         // Sender thread: takes frames from queue and writes to dataOut
         private void startSender() {
             senderThread = new Thread(() -> {
                 try {
                     while (!Thread.currentThread().isInterrupted()) {
                         Frame f = sendQueue.take();
                         try {
                             dataOut.writeInt(f.len);
                             if (f.len > 0 && f.data != null) dataOut.write(f.data, 0, f.len);
                             dataOut.flush();
                         } catch (IOException ioe) {
                             log("  ✗ Error writing frame to " + clientId + ": " + ioe.getMessage());
                             break;
                         }
                     }
                 } catch (InterruptedException ie) {
                     // Thread interrupted -> exit cleanly
                 } finally {
                     // Ensure client is closed if sender stops unexpectedly
                     close();
                 }
             }, clientId + "-sender");
             senderThread.setDaemon(true);
             senderThread.start();
         }

         ClientHandler(Socket socket, String clientId, String clientInfo) {
             this.socket = socket;
             this.clientId = clientId;
             this.clientInfo = clientInfo;
             try {
                 reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                 dataIn = new DataInputStream(socket.getInputStream());
                 dataOut = new DataOutputStream(socket.getOutputStream());
                 // Start per-client sender to write frames from queue to client
                 startSender();
             } catch (IOException e) {
                 log("✗ Error initializing " + clientId + ": " + e.getMessage());
             }
         }

         String getDisplayInfo() {
             return clientId + " [" + studentId + "] - " + clientInfo;
         }

         @Override
         public void run() {
             try {
                 // Nhận Student_ID
                 studentId = reader.readLine();
                 if (studentId != null && !studentId.isEmpty()) {
                     log("→ " + clientId + " Student_ID: " + studentId);

                     String response = calculateFirstResponse(studentId);
                     writer.write(response);
                     writer.newLine();
                     writer.flush();
                     log("← Sent to " + clientId + ": " + response);

                     updateClientList();
                 }

                 // Vòng lặp xử lý commands
                 String command;
                 while ((command = reader.readLine()) != null) {
                     log("\n→ " + clientId + " command: " + command);

                     if ("QUIT".equals(command)) {
                         break;
                     }
                     else if ("FILE".equals(command)) {
                         handleFileUpload();
                     }
                     else if ("BROADCAST_MSG".equals(command)) {
                         handleBroadcastMessage();
                     }
                     else {
                         handleMessage(command);
                     }
                 }
             } catch (IOException e) {
                 log("✗ " + clientId + " error: " + e.getMessage());
             } finally {
                 close();
                 clients.remove(this);
                 log("✗ " + clientId + " disconnected");
                 updateClientList();
             }
         }

         private String calculateFirstResponse(String id) {
             try {
                 return new BigInteger(id).multiply(BigInteger.valueOf(4)).toString();
             } catch (NumberFormatException e) {
                 return "ERROR: Invalid Student ID";
             }
         }

         private void handleMessage(String msg) throws IOException {
             String response = processMessage(msg);
             writer.write(response);
             writer.newLine();
             writer.flush();
             log("← Sent to " + clientId + ": " + response);
         }

         private String processMessage(String msg) {
             try {
                 BigInteger num = new BigInteger(msg);
                 if (num.compareTo(BigInteger.ZERO) > 0) {
                     return num.pow(4).toString();
                 } else {
                     return msg;
                 }
             } catch (NumberFormatException e) {
                 return msg;
             }
         }

         private void handleBroadcastMessage() throws IOException {
             String message = reader.readLine();
             log("  📢 Broadcasting message from " + clientId + ": " + message);

             String broadcastMsg = "[" + clientId + "]: " + message;
             broadcast(broadcastMsg, this);

             writer.write("BROADCAST_OK");
             writer.newLine();
             writer.flush();
         }

         private void handleFileUpload() throws IOException {
             try {
                 // Read filename and filesize, sanitize filename to prevent path traversal
                 String rawName = dataIn.readUTF();
                 String fileName = new File(rawName).getName(); // sanitize: keep only base name
                 long fileSize = dataIn.readLong();

                 log("  ↓ Receiving file from " + clientId + ": " + fileName + " (" + fileSize + " bytes)");

                 // Reject overly large files explicitly
                 if (fileSize > MAX_FILE_SIZE) {
                     log("  ✗ File too large (" + formatFileSize(fileSize) + ") — rejecting upload from " + clientId);
                     try { dataOut.writeUTF("ERROR: File too large"); dataOut.flush(); } catch (IOException ignored) {}
                     // Optionally, consume/skip frames or close connection. We'll return and let client handle.
                     return;
                 }

                 String savePath = UPLOAD_DIR + clientId + "_" + fileName;
                 long totalReceived = 0;
                 boolean cancelled = false;
                 long lastLogTime = System.currentTimeMillis();

                 // Notify other clients (send header) so they can prepare to receive streamed frames
                 synchronized (clients) {
                     for (ClientHandler c : clients) {
                         if (c != this) {
                             try {
                                 c.writer.write("BROADCAST_FILE");
                                 c.writer.newLine();
                                 c.writer.flush();
                                 c.dataOut.writeUTF(clientId + "_" + fileName);
                                 c.dataOut.writeLong(fileSize);
                                 c.dataOut.flush();
                             } catch (IOException ignored) {
                                 // ignore failures to notify a specific client
                             }
                         }
                     }
                 }

                 // Prepare buffer for streaming
                 byte[] buffer = new byte[4096];

                 // Set socket read timeout to avoid indefinite blocking when client stalls
                 try {
                     socket.setSoTimeout(UPLOAD_READ_TIMEOUT_MS);
                 } catch (SocketException ignored) {}

                 try (FileOutputStream fos = new FileOutputStream(savePath)) {
                     while (true) {
                         int frameLen;
                         try {
                             frameLen = dataIn.readInt();
                         } catch (SocketTimeoutException ste) {
                             cancelled = true;
                             log("    Timeout waiting for frame from " + clientId);
                             break;
                         } catch (EOFException eof) {
                             // client disconnected unexpectedly
                             cancelled = true;
                             log("    (EOF) Client disconnected while sending file");
                             break;
                         }

                         if (frameLen == -1) {
                             // client requested cancel
                             cancelled = true;
                             log("    Client sent CANCEL frame");
                             // enqueue CANCEL frame to other clients
                             synchronized (clients) {
                                 for (ClientHandler c : clients) {
                                     if (c != this) c.enqueueFrame(new Frame(-1, null));
                                 }
                             }
                             break;
                         }

                         if (frameLen == 0) {
                             // finished sending — enqueue EOF to others
                             synchronized (clients) {
                                 for (ClientHandler c : clients) {
                                     if (c != this) c.enqueueFrame(new Frame(0, null));
                                 }
                             }
                             break;
                         }

                         int remaining = frameLen;
                         while (remaining > 0) {
                             int toRead = Math.min(remaining, buffer.length);
                             int actuallyRead = dataIn.read(buffer, 0, toRead);
                             if (actuallyRead == -1) {
                                 cancelled = true;
                                 log("    Unexpected EOF while reading chunk");
                                 break;
                             }
                             // write to disk
                             fos.write(buffer, 0, actuallyRead);
                             totalReceived += actuallyRead;
                             remaining -= actuallyRead;

                             // Broadcast this chunk to other clients by enqueueing a copy into their queues
                             byte[] copy = Arrays.copyOf(buffer, actuallyRead);
                             synchronized (clients) {
                                 for (ClientHandler c : clients) {
                                     if (c != this) c.enqueueFrame(new Frame(actuallyRead, copy));
                                 }
                             }

                             long currentTime = System.currentTimeMillis();
                             if (currentTime - lastLogTime > 500 || totalReceived == fileSize) {
                                 int progress = (fileSize > 0) ? (int)((totalReceived * 100) / fileSize) : 0;
                                 log("    Progress: " + progress + "% (" + formatFileSize(totalReceived) +
                                         " / " + formatFileSize(fileSize) + ")");
                                 lastLogTime = currentTime;
                             }
                         }

                         if (cancelled) break;
                     }
                     fos.getFD().sync();
                 } finally {
                     // restore socket timeout (0 means infinite)
                     try { socket.setSoTimeout(0); } catch (SocketException ignored) {}
                 }

                 if (cancelled || totalReceived != fileSize) {
                     // delete partial file if exists
                     File partial = new File(savePath);
                     if (partial.exists()) {
                         if (!partial.delete()) log("    Warning: failed to delete partial file: " + savePath);
                     }

                     try {
                         if (cancelled) {
                             dataOut.writeUTF("CANCELLED");
                         } else {
                             dataOut.writeUTF("ERROR: Incomplete upload");
                         }
                         dataOut.flush();
                     } catch (IOException ignored) {}

                     log("  ✗ File upload incomplete or cancelled: " + savePath + "\n");
                 } else {
                     try {
                         dataOut.writeUTF("SUCCESS");
                         dataOut.flush();
                     } catch (IOException ignored) {}
                     log("  ✓ File saved and broadcasted: " + savePath + "\n");
                 }
             } catch (IOException e) {
                 log("  ✗ File upload error: " + e.getMessage());
                 try { dataOut.writeUTF("ERROR: " + e.getMessage()); dataOut.flush(); } catch (IOException ignored) {}
             }
         }

         void sendMessage(String message) {
             try {
                 writer.write("BROADCAST_MSG");
                 writer.newLine();
                 writer.write(message);
                 writer.newLine();
                 writer.flush();
             } catch (IOException e) {
                 log("  ✗ Error sending broadcast to " + clientId);
             }
         }

         // Backward-compatible sendFile: chunk a byte[] into frames and enqueue to client's queue
         void sendFile(String fileName, long fileSize, byte[] fileData) {
             try {
                 writer.write("BROADCAST_FILE");
                 writer.newLine();
                 writer.flush();

                 dataOut.writeUTF(fileName);
                 dataOut.writeLong(fileSize);
                 dataOut.flush();

                 int offset = 0;
                 while (offset < fileData.length) {
                     int len = Math.min(4096, fileData.length - offset);
                     byte[] copy = Arrays.copyOfRange(fileData, offset, offset + len);
                     if (!enqueueFrame(new Frame(len, copy))) break;
                     offset += len;
                 }

                 // EOF frame
                 enqueueFrame(new Frame(0, null));

                 log("  ✓ File scheduled to send to " + clientId);
             } catch (IOException e) {
                 log("  ✗ Error sending file to " + clientId);
             }
         }

         void close() {
             try {
                 if (reader != null) reader.close();
                 if (writer != null) writer.close();
                 if (dataIn != null) dataIn.close();
                 if (dataOut != null) dataOut.close();
                 if (socket != null && !socket.isClosed()) socket.close();
                 if (senderThread != null) senderThread.interrupt();
             } catch (IOException e) {
                 // Ignore
             }
         }
     }

     // Helper to format bytes into human-readable string (used in logs)
     private String formatFileSize(long bytes) {
         if (bytes < 1024) return bytes + " B";
         if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
         if (bytes < 1024L * 1024L * 1024L) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
         return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
     }

     public static void main(String[] args) {
         SwingUtilities.invokeLater(() -> {
             try {
                 UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
             } catch (Exception e) {
                 LOGGER.log(Level.SEVERE, "Failed to set system look and feel", e);
             }
             new ServerGUI().setVisible(true);
         });
     }
 }

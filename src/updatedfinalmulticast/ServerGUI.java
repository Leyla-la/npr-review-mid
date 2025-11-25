package updatedfinalmulticast;


import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.List;

public class ServerGUI extends JFrame {
    private static final int PORT = 4075;
    private static final String UPLOAD_DIR = "server_files/";

    private JTextArea txtLog;
    private JButton btnStart;
    private JButton btnStop;
    private JLabel lblStatus;
    private JLabel lblClientCount;
    private DefaultListModel<String> clientListModel;
    private JList<String> clientList;

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private int clientCounter = 0;

    public ServerGUI() {
        initializeGUI();
        new File(UPLOAD_DIR).mkdirs();
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
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
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

        ClientHandler(Socket socket, String clientId, String clientInfo) {
            this.socket = socket;
            this.clientId = clientId;
            this.clientInfo = clientInfo;
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                dataIn = new DataInputStream(socket.getInputStream());
                dataOut = new DataOutputStream(socket.getOutputStream());
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
                String fileName = dataIn.readUTF();
                long fileSize = dataIn.readLong();

                log("  ↓ Receiving file from " + clientId + ": " + fileName + " (" + fileSize + " bytes)");

                // Đọc file vào byte array
                byte[] fileData = new byte[(int)fileSize];
                dataIn.readFully(fileData);

                // Lưu file
                String savePath = UPLOAD_DIR + clientId + "_" + fileName;
                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    fos.write(fileData);
                }

                // Broadcast file tới clients khác
                log("  📢 Broadcasting file to other clients...");
                broadcastFile(clientId + "_" + fileName, fileSize, fileData, this);

                dataOut.writeUTF("SUCCESS");
                dataOut.flush();

                log("  ✓ File saved and broadcasted: " + savePath);

            } catch (IOException e) {
                log("  ✗ File upload error: " + e.getMessage());
                dataOut.writeUTF("ERROR: " + e.getMessage());
                dataOut.flush();
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

        void sendFile(String fileName, long fileSize, byte[] fileData) {
            try {
                writer.write("BROADCAST_FILE");
                writer.newLine();
                writer.flush();

                dataOut.writeUTF(fileName);
                dataOut.writeLong(fileSize);
                dataOut.write(fileData);
                dataOut.flush();

                log("  ✓ File sent to " + clientId);
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
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new ServerGUI().setVisible(true);
        });
    }
}
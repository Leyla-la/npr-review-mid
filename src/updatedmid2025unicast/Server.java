
package updatedmid2025unicast;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.util.*;
import java.util.List;

public class Server extends JFrame {
    private static final int PORT = 4075;
    private static final String UPLOAD_DIR = "server_files_unicast/";

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

    public Server() {
        initializeGUI();
        new File(UPLOAD_DIR).mkdirs();
    }

    private void initializeGUI() {
        setTitle("TCP Server - Unicast Mode");
        setSize(900, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(createControlPanel(), BorderLayout.NORTH);

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
        btnStart = new JButton("▶ Start Server");
        btnStart.setFont(new Font("Arial", Font.BOLD, 12));
        btnStart.addActionListener(e -> startServer());
        panel.add(btnStart, gbc);

        gbc.gridx = 1;
        btnStop = new JButton("⏹ Stop Server");
        btnStop.setFont(new Font("Arial", Font.BOLD, 12));
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
        txtLog.setBackground(new Color(240, 240, 240));
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

            log("╔════════════════════════════════════════╗");
            log("║       SERVER STARTED (UNICAST)        ║");
            log("╚════════════════════════════════════════╝");
            log("Port: " + PORT);
            log("Upload directory: " + UPLOAD_DIR);
            log("Waiting for clients...\n");

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

            log("\n╔════════════════════════════════════════╗");
            log("║          SERVER STOPPED               ║");
            log("╚════════════════════════════════════════╝\n");
            updateClientList();

        } catch (IOException e) {
            log("✗ Error stopping server: " + e.getMessage());
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
                // Nhận Student_ID đầu tiên
                studentId = reader.readLine();
                if (studentId != null && !studentId.isEmpty()) {
                    log("→ " + clientId + " sent Student_ID: " + studentId);

                    String response = calculateFirstResponse(studentId);
                    writer.write(response);
                    writer.newLine();
                    writer.flush();
                    log("← Sent to " + clientId + ": 4×" + studentId + " = " + response);

                    updateClientList();
                }

                // Vòng lặp xử lý commands
                String command;
                while ((command = reader.readLine()) != null) {
                    log("\n→ " + clientId + " command: " + command);

                    if ("QUIT".equals(command)) {
                        log("✓ " + clientId + " requested disconnect");
                        break;
                    }
                    else if ("FILE".equals(command)) {
                        handleFileUpload();
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
                log("✗ " + clientId + " disconnected\n");
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

            try {
                BigInteger num = new BigInteger(msg);
                if (num.compareTo(BigInteger.ZERO) > 0) {
                    log("← Sent to " + clientId + ": [" + msg + "]^4 = " + response);
                } else {
                    log("← Sent to " + clientId + ": (echo) " + response);
                }
            } catch (NumberFormatException e) {
                log("← Sent to " + clientId + ": (echo) " + response);
            }
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

        private void handleFileUpload() throws IOException {
            try {
                String fileName = dataIn.readUTF();
                long fileSize = dataIn.readLong();

                log("  ↓ Receiving file: " + fileName);
                log("    Size: " + formatFileSize(fileSize));

                String savePath = UPLOAD_DIR + clientId + "_" + fileName;

                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    byte[] buffer = new byte[4096];
                    long totalReceived = 0;
                    int bytesRead;
                    long lastLogTime = System.currentTimeMillis();

                    while (totalReceived < fileSize) {
                        int toRead = (int)Math.min(buffer.length, fileSize - totalReceived);
                        bytesRead = dataIn.read(buffer, 0, toRead);
                        if (bytesRead == -1) break;

                        fos.write(buffer, 0, bytesRead);
                        totalReceived += bytesRead;

                        // Log progress mỗi 500ms
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime > 500 || totalReceived == fileSize) {
                            int progress = (int)((totalReceived * 100) / fileSize);
                            log("    Progress: " + progress + "% (" + formatFileSize(totalReceived) +
                                    " / " + formatFileSize(fileSize) + ")");
                            lastLogTime = currentTime;
                        }
                    }
                }

                dataOut.writeUTF("SUCCESS");
                dataOut.flush();

                log("  ✓ File saved successfully: " + savePath + "\n");

            } catch (IOException e) {
                log("  ✗ File upload error: " + e.getMessage());
                dataOut.writeUTF("ERROR: " + e.getMessage());
                dataOut.flush();
            }
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
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
            new Server().setVisible(true);
        });
    }
}
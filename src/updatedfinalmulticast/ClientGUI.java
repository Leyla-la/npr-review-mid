package updatedfinalmulticast;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;

public class ClientGUI extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 4075;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;

    private JTextArea txtLog;
    private JTextField txtStudentId;
    private JTextField txtMessage;
    private JButton btnConnect;
    private JButton btnSend;
    private JButton btnBroadcast;
    private JButton btnSendFile;
    private JButton btnDisconnect;
    private JLabel lblStatus;
    private JProgressBar progressBar;

    private boolean isConnected = false;
    private Thread messageListener;

    public ClientGUI() {
        initializeGUI();
    }

    private void initializeGUI() {
        setTitle("TCP Client - Broadcast Mode");
        setSize(750, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(createConnectionPanel(), BorderLayout.NORTH);
        mainPanel.add(createLogPanel(), BorderLayout.CENTER);
        mainPanel.add(createMessagePanel(), BorderLayout.SOUTH);

        add(mainPanel);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                disconnect();
            }
        });
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Student ID:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStudentId = new JTextField("12345", 15);
        panel.add(txtStudentId, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnConnect = new JButton("Connect");
        btnConnect.addActionListener(e -> connect());
        panel.add(btnConnect, gbc);

        gbc.gridx = 3;
        btnDisconnect = new JButton("Disconnect");
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> disconnect());
        panel.add(btnDisconnect, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 12));
        panel.add(lblStatus, gbc);

        // Progress bar
        gbc.gridy = 2;
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        panel.add(progressBar, gbc);

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Communication Log"));

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        JScrollPane scrollPane = new JScrollPane(txtLog);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createMessagePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Send Message / File"));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.add(new JLabel("Message:"), BorderLayout.WEST);
        txtMessage = new JTextField();
        txtMessage.setEnabled(false);
        txtMessage.addActionListener(e -> sendMessage());
        inputPanel.add(txtMessage, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        btnSend = new JButton("Send (Unicast)");
        btnSend.setEnabled(false);
        btnSend.setToolTipText("Send to server only");
        btnSend.addActionListener(e -> sendMessage());
        btnPanel.add(btnSend);

        btnBroadcast = new JButton("📢 Broadcast");
        btnBroadcast.setEnabled(false);
        btnBroadcast.setToolTipText("Send to all connected clients");
        btnBroadcast.addActionListener(e -> broadcastMessage());
        btnPanel.add(btnBroadcast);

        btnSendFile = new JButton("📁 Send File (Broadcast)");
        btnSendFile.setEnabled(false);
        btnSendFile.addActionListener(e -> sendFile());
        btnPanel.add(btnSendFile);

        panel.add(btnPanel, BorderLayout.EAST);

        return panel;
    }

    private void connect() {
        String studentId = txtStudentId.getText().trim();
        if (studentId.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter Student ID!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("Connecting to " + HOST + ":" + PORT + "...");

            socket = new Socket(HOST, PORT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataIn = new DataInputStream(socket.getInputStream());
            dataOut = new DataOutputStream(socket.getOutputStream());

            isConnected = true;
            updateConnectionStatus(true);
            log("✓ Connected successfully!\n");

            // Gửi Student_ID
            log("→ Sending Student_ID: " + studentId);
            writer.write(studentId);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                log("← Received (4×ID): " + response + "\n");
            }

            // Start message listener thread
            startMessageListener();

        } catch (IOException e) {
            log("✗ Connection failed: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to server!\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            updateConnectionStatus(false);
        }
    }

    private void startMessageListener() {
        messageListener = new Thread(() -> {
            try {
                String line;
                while (isConnected && (line = reader.readLine()) != null) {
                    if ("BROADCAST_MSG".equals(line)) {
                        String message = reader.readLine();
                        log("📢 [BROADCAST] " + message);

                        // Hiển thị notification
                        SwingUtilities.invokeLater(() -> {
                            Toolkit.getDefaultToolkit().beep();
                        });
                    }
                    else if ("BROADCAST_FILE".equals(line)) {
                        receiveFile();
                    }
                }
            } catch (IOException e) {
                if (isConnected) {
                    log("✗ Connection lost: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> disconnect());
                }
            }
        });
        messageListener.start();
    }

    private void disconnect() {
        if (!isConnected) return;

        isConnected = false;

        try {
            log("\n→ Sending QUIT signal...");
            writer.write("QUIT");
            writer.newLine();
            writer.flush();

            Thread.sleep(100);

            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();

            if (messageListener != null) messageListener.interrupt();

            log("✓ Disconnected successfully\n");

        } catch (Exception e) {
            log("✗ Error during disconnect: " + e.getMessage() + "\n");
        } finally {
            updateConnectionStatus(false);
        }
    }

    private void sendMessage() {
        if (!isConnected) return;

        String message = txtMessage.getText().trim();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a message!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("→ Sending (Unicast): " + message);
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                try {
                    new java.math.BigInteger(message);
                    log("← Received ([" + message + "]^4): " + response + "\n");
                } catch (NumberFormatException e) {
                    log("← Received (echo): " + response + "\n");
                }
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Send error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    private void broadcastMessage() {
        if (!isConnected) return;

        String message = txtMessage.getText().trim();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a message!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("📢 Broadcasting: " + message);
            writer.write("BROADCAST_MSG");
            writer.newLine();
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if ("BROADCAST_OK".equals(response)) {
                log("✓ Message broadcasted to all clients\n");
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Broadcast error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    private void sendFile() {
        if (!isConnected) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select file to broadcast");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();

            // Disable buttons during upload
            setButtonsEnabled(false);
            progressBar.setVisible(true);
            progressBar.setValue(0);
            progressBar.setString("Preparing...");

            // Upload in background thread
            new Thread(() -> {
                try {
                    log("\n📢 Broadcasting file: " + file.getName() + " (" + file.length() + " bytes)");

                    writer.write("FILE");
                    writer.newLine();
                    writer.flush();

                    dataOut.writeUTF(file.getName());
                    dataOut.writeLong(file.length());
                    dataOut.flush();

                    // Upload with progress
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        long totalSent = 0;
                        long fileSize = file.length();

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            dataOut.write(buffer, 0, bytesRead);
                            totalSent += bytesRead;

                            int progress = (int)((totalSent * 100) / fileSize);
                            SwingUtilities.invokeLater(() -> {
                                progressBar.setValue(progress);
                                progressBar.setString("Uploading: " + progress + "%");
                            });
                        }
                        dataOut.flush();
                    }

                    String serverResponse = dataIn.readUTF();

                    if ("SUCCESS".equals(serverResponse)) {
                        log("✓ File broadcasted successfully!\n");
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setString("Complete!");
                            JOptionPane.showMessageDialog(this,
                                    "File broadcasted to all clients!",
                                    "Success", JOptionPane.INFORMATION_MESSAGE);
                        });
                    } else {
                        log("✗ Broadcast failed: " + serverResponse + "\n");
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setString("Failed!");
                            JOptionPane.showMessageDialog(this,
                                    "Broadcast failed: " + serverResponse,
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }

                } catch (IOException e) {
                    log("✗ File send error: " + e.getMessage() + "\n");
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setString("Error!");
                        JOptionPane.showMessageDialog(this,
                                "Cannot send file!\n" + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        setButtonsEnabled(true);
                        new Timer(2000, evt -> {
                            progressBar.setVisible(false);
                            progressBar.setValue(0);
                            ((Timer)evt.getSource()).stop();
                        }).start();
                    });
                }
            }).start();
        }
    }

    private void receiveFile() {
        try {
            String fileName = dataIn.readUTF();
            long fileSize = dataIn.readLong();

            log("\n📥 Receiving broadcast file: " + fileName + " (" + fileSize + " bytes)");

            SwingUtilities.invokeLater(() -> {
                progressBar.setVisible(true);
                progressBar.setValue(0);
                progressBar.setString("Downloading: 0%");
            });

            // Create downloads directory
            File downloadDir = new File("client_downloads/");
            downloadDir.mkdirs();

            File saveFile = new File(downloadDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                byte[] buffer = new byte[4096];
                long totalReceived = 0;
                int bytesRead;

                while (totalReceived < fileSize) {
                    int toRead = (int)Math.min(buffer.length, fileSize - totalReceived);
                    bytesRead = dataIn.read(buffer, 0, toRead);
                    if (bytesRead == -1) break;

                    fos.write(buffer, 0, bytesRead);
                    totalReceived += bytesRead;

                    int progress = (int)((totalReceived * 100) / fileSize);
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(progress);
                        progressBar.setString("Downloading: " + progress + "%");
                    });
                }
            }

            log("✓ File saved: " + saveFile.getAbsolutePath() + "\n");

            SwingUtilities.invokeLater(() -> {
                progressBar.setString("Download complete!");
                Toolkit.getDefaultToolkit().beep();

                new Timer(2000, evt -> {
                    progressBar.setVisible(false);
                    progressBar.setValue(0);
                    ((Timer)evt.getSource()).stop();
                }).start();
            });

        } catch (IOException e) {
            log("✗ File receive error: " + e.getMessage() + "\n");
            SwingUtilities.invokeLater(() -> {
                progressBar.setString("Download failed!");
                progressBar.setVisible(false);
            });
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        btnBroadcast.setEnabled(enabled);
        btnSendFile.setEnabled(enabled);
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtStudentId.setEnabled(!connected);
        txtMessage.setEnabled(connected);
        setButtonsEnabled(connected);

        if (connected) {
            lblStatus.setText("Status: Connected to " + HOST + ":" + PORT);
            lblStatus.setForeground(new Color(0, 150, 0));
        } else {
            lblStatus.setText("Status: Disconnected");
            lblStatus.setForeground(Color.RED);
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(message + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new ClientGUI().setVisible(true);
        });
    }
}
package updatedmid2025unicast;

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
    private JButton btnSendFile;
    private JButton btnDisconnect;
    private JLabel lblStatus;
    private JProgressBar progressBar;
    private JLabel lblProgressText;

    private boolean isConnected = false;

    public ClientGUI() {
        initializeGUI();
    }

    private void initializeGUI() {
        setTitle("TCP Client - Unicast Mode");
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

        // Row 0: Student ID + Buttons
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Student ID:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStudentId = new JTextField("12345", 15);
        panel.add(txtStudentId, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnConnect = new JButton("🔗 Connect");
        btnConnect.setFont(new Font("Arial", Font.BOLD, 11));
        btnConnect.addActionListener(e -> connect());
        panel.add(btnConnect, gbc);

        gbc.gridx = 3;
        btnDisconnect = new JButton("🔌 Disconnect");
        btnDisconnect.setFont(new Font("Arial", Font.BOLD, 11));
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> disconnect());
        panel.add(btnDisconnect, gbc);

        // Row 1: Status
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 12));
        panel.add(lblStatus, gbc);

        // Row 2: Progress bar
        gbc.gridy = 2;
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(0, 25));
        progressBar.setVisible(false);
        panel.add(progressBar, gbc);

        // Row 3: Progress text
        gbc.gridy = 3;
        lblProgressText = new JLabel(" ");
        lblProgressText.setFont(new Font("Arial", Font.PLAIN, 11));
        lblProgressText.setForeground(new Color(100, 100, 100));
        panel.add(lblProgressText, gbc);

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Communication Log"));

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtLog.setBackground(new Color(250, 250, 250));
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
        txtMessage.setFont(new Font("Arial", Font.PLAIN, 12));
        txtMessage.addActionListener(e -> sendMessage());
        inputPanel.add(txtMessage, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));

        btnSend = new JButton("📤 Send Message");
        btnSend.setEnabled(false);
        btnSend.setFont(new Font("Arial", Font.BOLD, 11));
        btnSend.addActionListener(e -> sendMessage());
        btnPanel.add(btnSend);

        btnSendFile = new JButton("📁 Send File");
        btnSendFile.setEnabled(false);
        btnSendFile.setFont(new Font("Arial", Font.BOLD, 11));
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
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log("Connecting to " + HOST + ":" + PORT + "...");

            socket = new Socket(HOST, PORT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataIn = new DataInputStream(socket.getInputStream());
            dataOut = new DataOutputStream(socket.getOutputStream());

            isConnected = true;
            updateConnectionStatus(true);
            log("✓ Connected successfully!");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            // Gửi Student_ID
            log("→ Sending Student_ID: " + studentId);
            writer.write(studentId);
            writer.newLine();
            writer.flush();

            // Nhận phản hồi 4*ID
            String response = reader.readLine();
            if (response != null) {
                log("← Received (4×" + studentId + "): " + response);
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            }

        } catch (IOException e) {
            log("✗ Connection failed: " + e.getMessage());
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to server!\n" + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            updateConnectionStatus(false);
        }
    }

    private void disconnect() {
        if (!isConnected) return;

        try {
            log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log("→ Sending QUIT signal...");
            writer.write("QUIT");
            writer.newLine();
            writer.flush();

            Thread.sleep(100);

            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();

            log("✓ Disconnected successfully");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        } catch (Exception e) {
            log("✗ Error during disconnect: " + e.getMessage());
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        } finally {
            isConnected = false;
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
            log("\n→ Sending: " + message);
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                try {
                    new java.math.BigInteger(message);
                    log("← Received ([" + message + "]^4): " + response);
                } catch (NumberFormatException e) {
                    log("← Received (echo): " + response);
                }
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            } else {
                log("✗ Server disconnected\n");
                disconnect();
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Send error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    private void sendFile() {
        if (!isConnected) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select file to send");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();

            // Disable buttons during upload
            setButtonsEnabled(false);
            showProgress(true);

            // Upload in background thread
            new Thread(() -> {
                try {
                    log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log("📁 Uploading file: " + file.getName());
                    log("   Size: " + formatFileSize(file.length()));

                    writer.write("FILE");
                    writer.newLine();
                    writer.flush();

                    dataOut.writeUTF(file.getName());
                    dataOut.writeLong(file.length());
                    dataOut.flush();

                    updateProgressText("Preparing file...");

                    // Upload with progress
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        long totalSent = 0;
                        long fileSize = file.length();
                        long startTime = System.currentTimeMillis();
                        long lastUpdateTime = startTime;

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            dataOut.write(buffer, 0, bytesRead);
                            totalSent += bytesRead;

                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateTime > 100 || totalSent == fileSize) {
                                int progress = (int)((totalSent * 100) / fileSize);
                                long elapsed = currentTime - startTime;
                                double speed = totalSent / (elapsed / 1000.0); // bytes/sec
                                long remaining = (long)((fileSize - totalSent) / speed);

                                updateProgress(progress);
                                updateProgressText(String.format(
                                        "Uploading: %s / %s | Speed: %s/s | ETA: %ds",
                                        formatFileSize(totalSent),
                                        formatFileSize(fileSize),
                                        formatFileSize((long)speed),
                                        remaining
                                ));

                                lastUpdateTime = currentTime;
                            }
                        }
                        dataOut.flush();
                    }

                    updateProgressText("Waiting for server confirmation...");

                    String serverResponse = dataIn.readUTF();

                    if ("SUCCESS".equals(serverResponse)) {
                        log("✓ File uploaded successfully!");
                        updateProgress(100);
                        updateProgressText("Upload complete!");

                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                    "File uploaded successfully!\n" + file.getName(),
                                    "Success", JOptionPane.INFORMATION_MESSAGE);
                        });
                    } else {
                        log("✗ Upload failed: " + serverResponse);
                        updateProgressText("Upload failed!");

                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                    "Upload failed: " + serverResponse,
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }

                    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

                } catch (IOException e) {
                    log("✗ File send error: " + e.getMessage());
                    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    updateProgressText("Error occurred!");

                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Cannot send file!\n" + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        setButtonsEnabled(true);
                        // Hide progress after 2 seconds
                        new Timer(2000, evt -> {
                            showProgress(false);
                            updateProgress(0);
                            updateProgressText(" ");
                            ((Timer)evt.getSource()).stop();
                        }).start();
                    });
                }
            }).start();
        }
    }

    private void updateProgress(int value) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(value);
            progressBar.setString(value + "%");
        });
    }

    private void updateProgressText(String text) {
        SwingUtilities.invokeLater(() -> lblProgressText.setText(text));
    }

    private void showProgress(boolean show) {
        SwingUtilities.invokeLater(() -> progressBar.setVisible(show));
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
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
            lblStatus.setText("Status: ✓ Connected to " + HOST + ":" + PORT);
            lblStatus.setForeground(new Color(0, 150, 0));
        } else {
            lblStatus.setText("Status: ✗ Disconnected");
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
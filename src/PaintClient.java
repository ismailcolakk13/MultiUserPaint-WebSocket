import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.*;
import java.util.Timer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MultiUserPaint - WebSocket İstemcisi
 * Aynı protokol, aynı arayüz — sadece iletişim katmanı WebSocket'e taşındı.
 */
public class PaintClient extends JFrame {

    private static final String SERVER_URI = "ws://localhost:8080";

    private WebSocketClient ws;
    private JPanel canvas;
    private int lastX, lastY;

    private final List<String> localHistory = new CopyOnWriteArrayList<>();
    private Rectangle selectionRect = null;
    private Point selectionStart = null;
    private final List<String> clipboard = new ArrayList<>();

    private Color currentColor = Color.BLACK;
    private int penSize = 2;
    private String userName;
    private String roomName;

    private JPopupMenu popupMenu;
    private int popupX, popupY;
    private JLabel lblStatus;

    // Oda listesi için senkronizasyon
    private final CountDownLatch roomLatch = new CountDownLatch(1);
    private final List<String> serverRooms = new ArrayList<>();

    // ── Kurucu ────────────────────────────────────────────────────────
    public PaintClient() {
        userName = JOptionPane.showInputDialog(null, "Kullanıcı Adınız:", "Giriş",
                JOptionPane.QUESTION_MESSAGE);
        if (userName == null || userName.trim().isEmpty())
            userName = "Kullanici" + (System.currentTimeMillis() % 100);

        setupUI();
        // connectBlocking ve await EDT'yi kilitler, invokeAndWait deadlock yaratır
        // → arka plan thread'inde çalıştır
        new Thread(this::connectAndSetup).start();
    }

    // ── WebSocket Bağlantısı + Oda Seçimi ────────────────────────────
    private void connectAndSetup() {
        try {
            ws = new WebSocketClient(new URI(SERVER_URI)) {

                @Override
                public void onOpen(ServerHandshake h) {
                    System.out.println("[WS] Bağlantı açıldı.");
                    send("ODALARI_GETIR");
                }

                @Override
                public void onMessage(String message) {
                    String[] parts = message.split("\\|");

                    if (parts[0].equals("ODALAR")) {
                        // Oda listesi geldi
                        for (int i = 1; i < parts.length; i++)
                            serverRooms.add(parts[i]);
                        roomLatch.countDown();

                    } else if (parts[0].equals("CIZ") || parts[0].equals("KUTU")) {
                        // Başka kullanıcıdan çizim/kes komutu
                        localHistory.add(message);
                        SwingUtilities.invokeLater(() -> canvas.repaint());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("[WS] Bağlantı kapandı. Sebep: " + reason);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, "Sunucu bağlantısı kesildi!",
                            "Bağlantı Kesildi", JOptionPane.WARNING_MESSAGE));
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("[WS] Hata: " + ex.getMessage());
                    roomLatch.countDown(); // Donmayı önle
                }
            };

            // Bağlan (max 3 saniye bekle)
            boolean connected = ws.connectBlocking(3, TimeUnit.SECONDS);
            if (!connected)
                throw new Exception("Sunucuya ulaşılamadı.");

            // Oda listesini bekle (max 3 saniye)
            roomLatch.await(3, TimeUnit.SECONDS);

            // EDT'de oda seçim diyaloğunu göster
            SwingUtilities.invokeAndWait(() -> showRoomDialog());

            // Odaya katıl
            ws.send("BAGLAN|" + userName + "|" + roomName);
            System.out.println("[WS] Odaya katıldı: " + roomName);

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Sunucuya bağlanılamadı!\nUygulama çevrimdışı modda açılacaktır.",
                    "Bağlantı Hatası", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void showRoomDialog() {
        List<String> options = new ArrayList<>(serverRooms);
        options.add(0, "-- Yeni Dosya (Oda) Oluştur --");

        String selected = (String) JOptionPane.showInputDialog(null,
                "Lütfen katılmak istediğiniz dosyayı seçin:",
                "Açık Dosyalar Listesi",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options.toArray(new String[0]),
                options.get(0));

        if (selected == null)
            System.exit(0);

        if (selected.equals(options.get(0))) {
            roomName = JOptionPane.showInputDialog(null, "Yeni Dosya Adı:",
                    "Dosya Oluştur", JOptionPane.QUESTION_MESSAGE);
            if (roomName == null || roomName.trim().isEmpty())
                roomName = "GenelDosya";
        } else {
            roomName = selected;
        }

        setTitle("MultiUserPaint | Kullanıcı: " + userName + " | Dosya: " + roomName);
    }

    // ── Arayüz Kurulumu ───────────────────────────────────────────────
    private void setupUI() {
        setTitle("MultiUserPaint");
        setSize(950, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // ── Sağ tık menüsü ──
        popupMenu = new JPopupMenu();
        JMenuItem itemCopy = new JMenuItem("📄 Kopyala");
        JMenuItem itemCut = new JMenuItem("✂️ Kes");
        JMenuItem itemPaste = new JMenuItem("📋 Buraya Yapıştır");

        itemCopy.addActionListener(e -> {
            copySelectedLines();
            showToast("✅ Kopyalandı!");
        });
        itemCut.addActionListener(e -> {
            cutSelectedLines();
            showToast("✂️ Kesildi!");
        });
        itemPaste.addActionListener(e -> {
            pasteAtLocation(popupX, popupY);
            showToast("📋 Yapıştırıldı!");
        });

        popupMenu.add(itemCopy);
        popupMenu.add(itemCut);
        popupMenu.addSeparator();
        popupMenu.add(itemPaste);

        // ── Araç çubuğu ──
        JToolBar toolBar = new JToolBar();

        JButton btnColor = new JButton("🎨 Renk Seç");
        btnColor.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnColor.setForeground(currentColor);
        btnColor.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Kalem Rengi Seç", currentColor);
            if (c != null) {
                currentColor = c;
                btnColor.setForeground(c);
            }
        });

        JLabel lblSize = new JLabel(" Kalınlık: 2px ");
        JSlider sliderSize = new JSlider(1, 20, 2);
        sliderSize.setMaximumSize(new Dimension(120, 40));
        sliderSize.addChangeListener(e -> {
            penSize = sliderSize.getValue();
            lblSize.setText(" Kalınlık: " + penSize + "px ");
        });

        lblStatus = new JLabel(" Durum: Hazır ");
        lblStatus.setForeground(Color.GRAY);
        lblStatus.setFont(new Font("SansSerif", Font.BOLD, 12));

        JLabel lblInfo = new JLabel(" | Sol Tık: Çiz | Sağ Tık + Sürükle: Seç | Sağ Tık: Menü ");
        lblInfo.setForeground(Color.GRAY);

        toolBar.add(btnColor);
        toolBar.addSeparator();
        toolBar.add(lblSize);
        toolBar.add(sliderSize);
        toolBar.addSeparator();
        toolBar.add(lblStatus);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(lblInfo);
        add(toolBar, BorderLayout.NORTH);

        // ── Tuval ──
        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                for (String lineData : localHistory) {
                    try {
                        String[] p = lineData.split("\\|");
                        if (p[0].equals("CIZ") && p.length >= 8) {
                            g2d.setColor(new Color(
                                    Integer.parseInt(p[5]),
                                    Integer.parseInt(p[6]),
                                    Integer.parseInt(p[7])));
                            int size = p.length >= 9 ? Integer.parseInt(p[8]) : 2;
                            g2d.setStroke(new BasicStroke(size,
                                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g2d.drawLine(
                                    Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                                    Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                        } else if (p[0].equals("KUTU") && p.length >= 5) {
                            g2d.setColor(Color.WHITE);
                            g2d.fillRect(
                                    Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                                    Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Seçim dikdörtgeni
                if (selectionRect != null && selectionRect.width > 0) {
                    g2d.setColor(Color.BLUE);
                    g2d.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER, 10f, new float[] { 5f, 5f }, 0f));
                    g2d.drawRect(selectionRect.x, selectionRect.y,
                            selectionRect.width, selectionRect.height);
                }
            }
        };
        canvas.setBackground(Color.WHITE);

        // ── Klavye kısayolları ──
        InputMap im = canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = canvas.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
        am.put("copy", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                copySelectedLines();
                showToast("Kopyalandı!");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "cut");
        am.put("cut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                cutSelectedLines();
                showToast("Kesildi!");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste");
        am.put("paste", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                popupMenu.setVisible(false);
                pasteAtLocation(canvas.getWidth() / 2, canvas.getHeight() / 2);
                showToast("Yapıştırıldı!");
            }
        });

        // ── Mouse olayları ──
        canvas.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    selectionStart = e.getPoint();
                    selectionRect = new Rectangle(selectionStart);
                } else {
                    selectionRect = null;
                    canvas.repaint();
                    lastX = e.getX();
                    lastY = e.getY();
                }
            }

            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    popupX = e.getX();
                    popupY = e.getY();
                    itemCopy.setEnabled(selectionRect != null && selectionRect.width > 0);
                    itemCut.setEnabled(selectionRect != null && selectionRect.width > 0);
                    popupMenu.show(canvas, popupX, popupY);
                    canvas.repaint();
                }
            }
        });

        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int x = Math.min(selectionStart.x, e.getX());
                    int y = Math.min(selectionStart.y, e.getY());
                    selectionRect = new Rectangle(x, y,
                            Math.abs(e.getX() - selectionStart.x),
                            Math.abs(e.getY() - selectionStart.y));
                    canvas.repaint();
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    int x = e.getX(), y = e.getY();
                    String cmd = "CIZ|" + lastX + "|" + lastY + "|" + x + "|" + y + "|"
                            + currentColor.getRed() + "|" + currentColor.getGreen() + "|"
                            + currentColor.getBlue() + "|" + penSize;
                    localHistory.add(cmd);
                    canvas.repaint();
                    // WebSocket üzerinden gönder
                    if (ws != null && ws.isOpen())
                        ws.send(cmd);
                    lastX = x;
                    lastY = y;
                }
            }
        });

        add(canvas, BorderLayout.CENTER);
        setVisible(true);
    }

    // ── Kopyala / Kes / Yapıştır ──────────────────────────────────────
    private void performCopy() {
        if (selectionRect == null || selectionRect.width == 0)
            return;
        clipboard.clear();
        for (String line : localHistory) {
            String[] p = line.split("\\|");
            if (p[0].equals("CIZ") && p.length >= 5) {
                int sx = Integer.parseInt(p[1]);
                int sy = Integer.parseInt(p[2]);
                if (selectionRect.contains(sx, sy))
                    clipboard.add(line);
            }
        }
    }

    private void copySelectedLines() {
        performCopy();
        selectionRect = null;
        canvas.repaint();
    }

    private void cutSelectedLines() {
        if (selectionRect == null || selectionRect.width == 0)
            return;
        performCopy();
        String cmd = "KUTU|" + selectionRect.x + "|" + selectionRect.y
                + "|" + selectionRect.width + "|" + selectionRect.height;
        localHistory.add(cmd);
        if (ws != null && ws.isOpen())
            ws.send(cmd); // Sunucuya gönder → diğerlerine yayınlanır
        selectionRect = null;
        canvas.repaint();
    }

    private void pasteAtLocation(int destX, int destY) {
        if (clipboard.isEmpty()) {
            showToast("Hafıza Boş!");
            return;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (String line : clipboard) {
            String[] p = line.split("\\|");
            if (p[0].equals("CIZ") && p.length >= 5) {
                minX = Math.min(minX, Math.min(Integer.parseInt(p[1]), Integer.parseInt(p[3])));
                minY = Math.min(minY, Math.min(Integer.parseInt(p[2]), Integer.parseInt(p[4])));
            }
        }

        int offX = destX - minX, offY = destY - minY;

        for (String line : clipboard) {
            String[] p = line.split("\\|");
            if (p[0].equals("CIZ") && p.length >= 8) {
                int size = p.length >= 9 ? Integer.parseInt(p[8]) : 2;
                String cmd = "CIZ|"
                        + (Integer.parseInt(p[1]) + offX) + "|"
                        + (Integer.parseInt(p[2]) + offY) + "|"
                        + (Integer.parseInt(p[3]) + offX) + "|"
                        + (Integer.parseInt(p[4]) + offY) + "|"
                        + p[5] + "|" + p[6] + "|" + p[7] + "|" + size;
                localHistory.add(cmd);
                if (ws != null && ws.isOpen())
                    ws.send(cmd); // Sunucuya gönder → diğerlerine yayınlanır
            }
        }
        selectionRect = null;
        canvas.repaint();
    }

    // ── Durum mesajı ──────────────────────────────────────────────────
    private void showToast(String message) {
        lblStatus.setText(" " + message + " ");
        lblStatus.setForeground(new Color(0, 150, 0));
        javax.swing.Timer t = new javax.swing.Timer(2000, e -> {
            lblStatus.setText(" Durum: Hazır ");
            lblStatus.setForeground(Color.GRAY);
        });
        t.setRepeats(false);
        t.start();
    }

    // ── Main ──────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(PaintClient::new);
    }
}

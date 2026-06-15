import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MultiUserPaint - WebSocket Sunucusu
 *
 * Protokol (aynı kalıyor):
 *   ODALARI_GETIR          → ODALAR|oda1|oda2|...
 *   BAGLAN|kullanici|oda   → geçmişi gönderir
 *   CIZ|x1|y1|x2|y2|r|g|b|size  → odadaki herkese yayın
 *   KUTU|x|y|w|h           → odadaki herkese yayın (kes işlemi)
 */
public class PaintServer extends WebSocketServer {

    // Oda adı → bağlı istemciler
    private final Map<String, List<WebSocket>> roomClients  = new ConcurrentHashMap<>();
    // Oda adı → çizim geçmişi
    private final Map<String, List<String>>    roomHistories = new ConcurrentHashMap<>();
    // Bağlantı → hangi odada olduğu
    private final Map<WebSocket, String>       clientRooms  = new ConcurrentHashMap<>();

    public PaintServer(int port) {
        super(new InetSocketAddress(port));
    }

    // ── Bağlantı Açıldı ──────────────────────────────────────────────
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[+] Yeni bağlantı: " + conn.getRemoteSocketAddress());
    }

    // ── Bağlantı Kapandı ─────────────────────────────────────────────
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String room = clientRooms.remove(conn);
        if (room != null && roomClients.containsKey(room)) {
            roomClients.get(room).remove(conn);
            System.out.println("[-] Ayrıldı: " + conn.getRemoteSocketAddress() + " | Oda: " + room);
        }
    }

    // ── Mesaj Geldi ───────────────────────────────────────────────────
    @Override
    public void onMessage(WebSocket conn, String message) {
        String[] parts = message.split("\\|");
        String   type  = parts[0];

        switch (type) {

            case "ODALARI_GETIR": {
                StringBuilder sb = new StringBuilder("ODALAR");
                for (String room : roomHistories.keySet())
                    sb.append("|").append(room);
                conn.send(sb.toString());
                break;
            }

            case "BAGLAN": {
                if (parts.length < 3) break;
                String roomName = parts[2];

                roomClients .putIfAbsent(roomName, new CopyOnWriteArrayList<>());
                roomHistories.putIfAbsent(roomName, new CopyOnWriteArrayList<>());

                roomClients.get(roomName).add(conn);
                clientRooms.put(conn, roomName);

                System.out.println("[*] Katıldı: " + parts[1] + " → " + roomName);

                // Geçmişi yeni kullanıcıya gönder
                for (String cmd : roomHistories.get(roomName))
                    conn.send(cmd);
                break;
            }

            case "CIZ":
            case "KUTU": {
                String room = clientRooms.get(conn);
                if (room == null) break;

                roomHistories.get(room).add(message);

                // Odadaki diğer herkese yayınla
                for (WebSocket c : roomClients.get(room)) {
                    if (c != conn && c.isOpen())
                        c.send(message);
                }
                break;
            }

            default:
                System.out.println("[?] Bilinmeyen komut: " + message);
        }
    }

    // ── Hata ─────────────────────────────────────────────────────────
    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[!] Hata: " + ex.getMessage());
    }

    // ── Sunucu Başladı ────────────────────────────────────────────────
    @Override
    public void onStart() {
        System.out.println("[✓] WebSocket sunucu 8080 portunda çalışıyor.");
        loadAutosaves();
        startAutoSave();
    }

    // ── Otomatik Kayıt (30 sn) ────────────────────────────────────────
    private void startAutoSave() {
        new Timer(true).scheduleAtFixedRate(new TimerTask() {
            public void run() {
                for (Map.Entry<String, List<String>> entry : roomHistories.entrySet()) {
                    String room = entry.getKey();
                    try (PrintWriter pw = new PrintWriter(new FileWriter("autosave_" + room + ".txt"))) {
                        for (String cmd : entry.getValue()) pw.println(cmd);
                        System.out.println("[💾] Otomatik kayıt: " + room);
                    } catch (IOException e) {
                        System.err.println("[!] Kayıt hatası: " + e.getMessage());
                    }
                }
            }
        }, 30_000, 30_000);
    }

    // ── Önceki Kayıtları Yükle ────────────────────────────────────────
    private void loadAutosaves() {
        File[] files = new File(".").listFiles(
                (d, n) -> n.startsWith("autosave_") && n.endsWith(".txt"));
        if (files == null) return;

        for (File f : files) {
            String room = f.getName().substring(9, f.getName().length() - 4);
            roomHistories.putIfAbsent(room, new CopyOnWriteArrayList<>());
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null)
                    roomHistories.get(room).add(line);
                System.out.println("[📂] Yüklendi: " + room + " (" + roomHistories.get(room).size() + " komut)");
            } catch (IOException e) {
                System.err.println("[!] Yükleme hatası: " + e.getMessage());
            }
        }
    }

    // ── Main ──────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        PaintServer server = new PaintServer(8080);
        server.start();
    }
}

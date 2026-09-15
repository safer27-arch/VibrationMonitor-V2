package com.example.vibrationmonitor;

public class TelegramSender {

    public interface Callback {
        void onResult(boolean success, String message);
    }

    public static void sendMessage(
            String token,
            String chatId,
            String message,
            Callback callback
    ) {
        new Thread(() -> {
            try {
                int code = sendTextInternal(token, chatId, message);
                callback.onResult(code >= 200 && code < 300, "HTTP " + code);
            } catch (Exception e) {
                callback.onResult(false, e.getMessage());
            }
        }).start();
    }

    public static void sendEventBundle(
            String token,
            String chatId,
            String caption,
            java.io.File photoFile,
            java.io.File graphFile,
            java.io.File csvFile,
            Callback callback
    ) {
        new Thread(() -> {
            try {
                boolean photoReady = false;

                if (photoFile != null) {
                    for (int i = 0; i < 10; i++) {
                        if (photoFile.exists() && photoFile.length() > 0) {
                            photoReady = true;
                            break;
                        }
                        Thread.sleep(300);
                    }
                }

                int firstCode;
                if (photoReady) {
                    firstCode = sendMultipart(
                            token,
                            "sendPhoto",
                            chatId,
                            "photo",
                            photoFile,
                            "image/jpeg",
                            "caption",
                            caption
                    );
                } else {
                    firstCode = sendTextInternal(token, chatId, caption);
                }

                int graphCode = 200;

                if (graphFile != null &&
                        graphFile.exists() &&
                        graphFile.length() > 0) {

                    graphCode = sendMultipart(
                            token,
                            "sendPhoto",
                            chatId,
                            "photo",
                            graphFile,
                            "image/png",
                            "caption",
                            "📈 같은 이벤트의 진동 상세 그래프"
                    );
                }

                int csvCode = 200;
                if (csvFile != null && csvFile.exists() && csvFile.length() > 0) {
                    csvCode = sendMultipart(
                            token,
                            "sendDocument",
                            chatId,
                            "document",
                            csvFile,
                            "text/csv",
                            "caption",
                            "📎 같은 이벤트의 CSV 원본 데이터"
                    );
                }

                boolean ok =
                        firstCode >= 200 && firstCode < 300 &&
                        graphCode >= 200 && graphCode < 300 &&
                        csvCode >= 200 && csvCode < 300;

                callback.onResult(
                        ok,
                        "PHOTO/TEXT HTTP " + firstCode +
                        ", GRAPH HTTP " + graphCode +
                        ", CSV HTTP " + csvCode
                );

            } catch (Exception e) {
                callback.onResult(false, e.getMessage());
            }
        }).start();
    }

    private static int sendTextInternal(
            String token,
            String chatId,
            String message
    ) throws Exception {
        String urlText =
                "https://api.telegram.org/bot" + token +
                "/sendMessage?chat_id=" +
                java.net.URLEncoder.encode(chatId, "UTF-8") +
                "&text=" +
                java.net.URLEncoder.encode(message, "UTF-8");

        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlText);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int sendMultipart(
            String token,
            String method,
            String chatId,
            String fileField,
            java.io.File file,
            String mimeType,
            String textField,
            String textValue
    ) throws Exception {

        String boundary = "----VibrationMonitor" + System.currentTimeMillis();
        java.net.HttpURLConnection conn = null;

        try {
            java.net.URL url = new java.net.URL(
                    "https://api.telegram.org/bot" + token + "/" + method
            );

            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=" + boundary
            );

            try (java.io.DataOutputStream out =
                         new java.io.DataOutputStream(conn.getOutputStream())) {

                writeTextPart(out, boundary, "chat_id", chatId);

                if (textField != null && textValue != null) {
                    writeTextPart(out, boundary, textField, textValue);
                }

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes(
                        "Content-Disposition: form-data; name=\"" +
                        fileField +
                        "\"; filename=\"" +
                        file.getName().replace("\"", "") +
                        "\"\r\n"
                );
                out.writeBytes("Content-Type: " + mimeType + "\r\n\r\n");

                try (java.io.FileInputStream in =
                             new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }

                out.writeBytes("\r\n");
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
            }

            return conn.getResponseCode();

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeTextPart(
            java.io.DataOutputStream out,
            String boundary,
            String name,
            String value
    ) throws Exception {

        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes(
                "Content-Disposition: form-data; name=\"" +
                name +
                "\"\r\n"
        );
        out.writeBytes(
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
        );
        out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    public static void sendMapPhoto(
            String token,
            String chatId,
            java.io.File mapFile,
            String caption,
            Callback callback
    ) {
        new Thread(() -> {
            try {
                int code = sendMultipart(
                        token,
                        "sendPhoto",
                        chatId,
                        "photo",
                        mapFile,
                        "image/png",
                        "caption",
                        caption
                );

                callback.onResult(
                        code >= 200 && code < 300,
                        "MAP HTTP " + code
                );

            } catch (Exception e) {
                callback.onResult(false, e.getMessage());
            }
        }).start();
    }


    private static String[] splitChatIds(String raw) {
        if (raw == null) return new String[0];
        return raw.trim().split("[\\s,;]+");
    }

    public static void sendMessageMulti(String token, String chatIds, String message, Callback callback) {
        new Thread(() -> {
            try {
                int ok=0, total=0; String last="";
                for(String id:splitChatIds(chatIds)) { if(id.trim().isEmpty()) continue; total++; int c=sendTextInternal(token,id.trim(),message); if(c>=200&&c<300)ok++; last="HTTP "+c; }
                callback.onResult(total>0 && ok==total, ok+"/"+total+" success, "+last);
            } catch(Exception e){callback.onResult(false,e.getMessage());}
        }).start();
    }

    public static void sendEventBundleMulti(String token,String chatIds,String caption,java.io.File photoFile,java.io.File graphFile,java.io.File csvFile,Callback callback){
        new Thread(() -> {
            int ok=0,total=0; String last="";
            try {
                for(String id:splitChatIds(chatIds)) { if(id.trim().isEmpty())continue; total++;
                    final Object lock=new Object(); final boolean[] done={false}; final boolean[] good={false}; final String[] msg={""};
                    sendEventBundle(token,id.trim(),caption,photoFile,graphFile,csvFile,(success,message)->{synchronized(lock){good[0]=success;msg[0]=message;done[0]=true;lock.notifyAll();}});
                    synchronized(lock){while(!done[0])lock.wait(60000);} if(good[0])ok++; last=msg[0];
                }
                callback.onResult(total>0&&ok==total,ok+"/"+total+" recipients, "+last);
            } catch(Exception e){callback.onResult(false,e.getMessage());}
        }).start();
    }

    public static void sendMapPhotoMulti(String token,String chatIds,java.io.File mapFile,String caption,Callback callback){
        new Thread(() -> {try{int ok=0,total=0;String last="";for(String id:splitChatIds(chatIds)){if(id.trim().isEmpty())continue;total++;int c=sendMultipart(token,"sendPhoto",id.trim(),"photo",mapFile,"image/png","caption",caption);if(c>=200&&c<300)ok++;last="HTTP "+c;}callback.onResult(total>0&&ok==total,ok+"/"+total+" success, "+last);}catch(Exception e){callback.onResult(false,e.getMessage());}}).start();
    }
}

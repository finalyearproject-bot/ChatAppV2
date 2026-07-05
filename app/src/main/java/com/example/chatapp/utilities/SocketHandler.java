package com.example.chatapp.utilities;

import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;

public class SocketHandler {
    private static Socket mSocket;

    public static synchronized void setSocket() {
        try {
            // ⚠️ IF USING EMULATOR, USE THIS EXACT IP:
            mSocket = IO.socket("http://10.0.2.2:8080");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public static synchronized Socket getSocket() {
        return mSocket;
    }
}
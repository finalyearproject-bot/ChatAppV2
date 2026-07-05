package com.example.chatapp.models;
import java.nio.ByteBuffer;

public class RatchetHeader {
    public byte[] dhPub;
    public int pn;
    public int n;
    public byte[] ciphertext;

    public RatchetHeader(byte[] dhPub, int pn, int n) {
        this.dhPub = dhPub;
        this.pn = pn;
        this.n = n;
    }

    // Required for AES-GCM Associated Data
    public byte[] getAssociatedData() {
        ByteBuffer buffer = ByteBuffer.allocate(dhPub.length + 8);
        buffer.put(dhPub);
        buffer.putInt(pn);
        buffer.putInt(n);
        return buffer.array();
    }
}
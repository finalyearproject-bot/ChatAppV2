package com.example.chatapp.activities;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.adapters.ChatAdapter;
import com.example.chatapp.crypto.DoubleRatchet;
import com.example.chatapp.crypto.X3DHManager;
import com.example.chatapp.databinding.ActivityChatBinding;
import com.example.chatapp.models.ChatMessage;
import com.example.chatapp.models.PreKeyBundle;
import com.example.chatapp.models.RatchetHeader;
import com.example.chatapp.models.User;
import com.example.chatapp.network.ApiClient;
import com.example.chatapp.network.ApiService;
import com.example.chatapp.utilities.Constants;
import com.example.chatapp.utilities.LocalDBManager;
import com.example.chatapp.utilities.PreferenceManager;
import com.example.chatapp.utilities.SocketHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.socket.client.Socket;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private User receiverUser;
    private List<ChatMessage> chatMessages;
    private ChatAdapter chatAdapter;
    private PreferenceManager preferenceManager;
    private Socket mSocket;

    private DoubleRatchet sessionRatchet;
    private X3DHManager.X3DHResult activeX3DH;
    private String myIdentityPublicBase64;

    private PreKeyBundle fetchedPeerBundle;
    private LocalDBManager localDB;

    // Logcat Tag for filtering
    private static final String TAG = "CRYPTO_PROTOCOL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        loadReceiverDetails();
        init();
    }

    private void init() {
        preferenceManager = new PreferenceManager(getApplicationContext());
        localDB = new LocalDBManager(this);

        // 🔥 LOAD ENCRYPTION STATE FROM MEMORY 🔥
        loadSessionState();

        chatMessages = new ArrayList<>();
        chatAdapter = new ChatAdapter(
                chatMessages,
                preferenceManager.getString(Constants.KEY_USER_ID),
                getBitmapFromEncodedString(receiverUser.image)
        );
        binding.chatRecyclerView.setAdapter(chatAdapter);

        // Socket Registration
        mSocket = SocketHandler.getSocket();
        mSocket.on(Socket.EVENT_CONNECT, args -> {
            runOnUiThread(() -> {
                try {
                    JSONObject data = new JSONObject();
                    data.put("phone", preferenceManager.getString(Constants.KEY_PHONE));
                    mSocket.emit("register", data);
                } catch (JSONException e) { e.printStackTrace(); }
            });
        });
        if(!mSocket.connected()) mSocket.connect();

        loadChatHistory();
        listenMessages();
        initializeCryptoSession();

        binding.layoutSend.setOnClickListener(v -> sendMessage());
        binding.imageBack.setOnClickListener(v -> onBackPressed());
    }

    // ==========================================
    // 💾 STATE PERSISTENCE & PROTOCOL LOGGING
    // ==========================================

    private void saveSessionState() {
        if (sessionRatchet != null) {
            String state = sessionRatchet.serializeState();
            // Using .phone guarantees the session is saved correctly without null ID issues
            preferenceManager.putString("session_" + receiverUser.phone, state);
            Log.d(TAG, "-> State saved to disk. Ratchet advanced.");
        }
    }

    private void loadSessionState() {
        // Using .phone guarantees the session is loaded correctly
        String state = preferenceManager.getString("session_" + receiverUser.phone);

        if (state != null) {
            sessionRatchet = DoubleRatchet.restoreState(state);

            // Extract the saved Root Key to prove it matches
            try {
                JSONObject obj = new JSONObject(state);
                String rkBase64 = obj.optString("RK", "");
                byte[] rkBytes = Base64.decode(rkBase64, Base64.NO_WRAP);

                Log.d(TAG, "\n=======================================================");
                Log.d(TAG, "🔓 CHAT OPENED: " + receiverUser.name);
                Log.d(TAG, "   Session State Successfully Restored from Memory.");
                Log.d(TAG, "   Active Root Key (RK): " + bytesToHex(rkBytes));
                Log.d(TAG, "=======================================================\n");
            } catch (Exception e) {
                Log.e(TAG, "Error logging state.", e);
            }
        } else {
            Log.d(TAG, "\n=======================================================");
            Log.d(TAG, "⚠️ NO PREVIOUS SESSION FOUND FOR: " + receiverUser.name);
            Log.d(TAG, "   Waiting to initiate X3DH Handshake on first message.");
            Log.d(TAG, "=======================================================\n");
        }
    }

    // ==========================================
    // 🔐 CORE LOGIC
    // ==========================================

    private void initializeCryptoSession() {
        ApiService apiService = ApiClient.getClient().create(ApiService.class);

        apiService.getPreKeyBundle(receiverUser.phone).enqueue(new Callback<PreKeyBundle>() {
            @Override
            public void onResponse(Call<PreKeyBundle> call, Response<PreKeyBundle> response) {
                if (response.isSuccessful() && response.body() != null) {
                    fetchedPeerBundle = response.body();
                    Log.d(TAG, "-> PreKey Bundle fetched from server.");
                }
            }
            @Override
            public void onFailure(Call<PreKeyBundle> call, Throwable t) {
                showToast("Failed to fetch secure keys.");
            }
        });
    }

    private void loadChatHistory() {
        String myId = preferenceManager.getString(Constants.KEY_USER_ID);
        String peerId = receiverUser.id;

        chatMessages.clear();
        chatMessages.addAll(localDB.getLocalChatHistory(myId, peerId));

        chatAdapter.notifyDataSetChanged();
        if (chatMessages.size() > 0) {
            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);
        }
    }

    private void sendMessage() {
        if (binding.inputMessage.getText().toString().isEmpty()) return;
        String text = binding.inputMessage.getText().toString();

        if (sessionRatchet == null) {
            if (fetchedPeerBundle == null) {
                showToast("Still fetching secure keys... please wait.");
                return;
            }
            try {
                String myName = preferenceManager.getString(Constants.KEY_NAME);
                myIdentityPublicBase64 = preferenceManager.getString("my_identity_public");

                byte[] myIdPrivBytes = Base64.decode(preferenceManager.getString("my_identity_private"), Base64.NO_WRAP);
                byte[] myIdPubBytes = Base64.decode(myIdentityPublicBase64, Base64.NO_WRAP);

                KeyFactory kf = KeyFactory.getInstance("X25519", "BC");
                KeyPair myIdentityKey = new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(myIdPubBytes)),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(myIdPrivBytes))
                );

                activeX3DH = X3DHManager.initiateHandshake(fetchedPeerBundle, myIdentityKey, myName, receiverUser.name);
                sessionRatchet = activeX3DH.ratchet;

                // 🔥 NEW: LOG THE NEWLY GENERATED KEY IMMEDIATELY 🔥
                try {
                    JSONObject obj = new JSONObject(sessionRatchet.serializeState());
                    String rkBase64 = obj.optString("RK", "");
                    Log.d(TAG, "\n=======================================================");
                    Log.d(TAG, "🚀 NEW E2EE SESSION ESTABLISHED WITH: " + receiverUser.name);
                    Log.d(TAG, "   Active Root Key (RK): " + bytesToHex(Base64.decode(rkBase64, Base64.NO_WRAP)));
                    Log.d(TAG, "=======================================================\n");
                } catch (Exception ex) { ex.printStackTrace(); }

            } catch (Exception e) {
                Log.e(TAG, "INITIATOR SETUP FAILED:", e);
                showToast("Crypto Setup Failed: " + e.getMessage());
                return;
            }
        }

        try {
            Log.d(TAG, "\n--- ENCRYPTING OUTGOING MESSAGE ---");
            RatchetHeader header = sessionRatchet.encrypt(text.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "-> Message Encrypted. Ratchet Turn (Ns): " + header.n);

            // 🔥 SAVE ENCRYPTION STATE AFTER ENCRYPTING 🔥
            saveSessionState();

            JSONObject message = new JSONObject();
            message.put("sender", preferenceManager.getString(Constants.KEY_PHONE));
            message.put("receiver", receiverUser.phone);
            message.put("ciphertext", Base64.encodeToString(header.ciphertext, Base64.NO_WRAP));
            message.put("dhPub", Base64.encodeToString(header.dhPub, Base64.NO_WRAP));
            message.put("pn", header.pn);
            message.put("n", header.n);

            if (activeX3DH != null) {
                message.put("x3dh_ek", activeX3DH.ephemeralPublicBase64);
                message.put("x3dh_ik", myIdentityPublicBase64);
            }

            mSocket.emit("send_message", message);
            Log.d(TAG, "-> Ciphertext dispatched to server.\n");

            ChatMessage chatMessage = new ChatMessage();
            chatMessage.senderId = preferenceManager.getString(Constants.KEY_USER_ID);
            chatMessage.message = text;
            chatMessage.dateTime = getReadableDateTime(new Date());
            chatMessage.status = 1;

            localDB.saveMessage(chatMessage.senderId, receiverUser.id, text, chatMessage.dateTime);

            chatMessages.add(chatMessage);
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);
            binding.inputMessage.setText(null);

            activeX3DH = null;

        } catch (Exception e) {
            e.printStackTrace();
            showToast("Failed to encrypt message.");
        }
    }

    private void listenMessages() {
        mSocket.on("receive_message", args -> {
            runOnUiThread(() -> {
                JSONObject data = (JSONObject) args[0];
                try {
                    String sender = data.getString("sender");

                    if (sender.equals(receiverUser.phone)) {
                        Log.d(TAG, "\n--- INCOMING CIPHERTEXT RECEIVED ---");

                        if (sessionRatchet == null && data.has("x3dh_ek") && data.has("x3dh_ik")) {
                            try {
                                byte[] myIdPrivBytes = Base64.decode(preferenceManager.getString("my_identity_private"), Base64.NO_WRAP);
                                byte[] myIdPubBytes = Base64.decode(preferenceManager.getString("my_identity_public"), Base64.NO_WRAP);
                                byte[] mySpkPrivBytes = Base64.decode(preferenceManager.getString("my_signed_prekey_private"), Base64.NO_WRAP);
                                byte[] mySpkPubBytes = Base64.decode(preferenceManager.getString("my_signed_prekey_public"), Base64.NO_WRAP);

                                KeyFactory kf = KeyFactory.getInstance("X25519", "BC");
                                KeyPair myIdentity = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(myIdPubBytes)), kf.generatePrivate(new PKCS8EncodedKeySpec(myIdPrivBytes)));
                                KeyPair mySignedPreKey = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(mySpkPubBytes)), kf.generatePrivate(new PKCS8EncodedKeySpec(mySpkPrivBytes)));

                                String peerEk = data.getString("x3dh_ek");
                                String peerIk = data.getString("x3dh_ik");

                                sessionRatchet = X3DHManager.receiveHandshake(myIdentity, mySignedPreKey, peerIk, peerEk);

                                // 🔥 NEW: LOG THE NEWLY GENERATED KEY IMMEDIATELY 🔥
                                try {
                                    JSONObject obj = new JSONObject(sessionRatchet.serializeState());
                                    String rkBase64 = obj.optString("RK", "");
                                    Log.d(TAG, "\n=======================================================");
                                    Log.d(TAG, "🚀 NEW E2EE SESSION RECEIVED FROM: " + sender);
                                    Log.d(TAG, "   Active Root Key (RK): " + bytesToHex(Base64.decode(rkBase64, Base64.NO_WRAP)));
                                    Log.d(TAG, "=======================================================\n");
                                } catch (Exception ex) { ex.printStackTrace(); }

                            } catch (Exception setupEx) {
                                Log.e(TAG, "RESPONDER SETUP FAILED: ", setupEx);
                            }
                        }

                        if (sessionRatchet != null) {
                            try {
                                byte[] cipherBytes = Base64.decode(data.getString("ciphertext"), Base64.NO_WRAP);
                                byte[] dhPubBytes = Base64.decode(data.getString("dhPub"), Base64.NO_WRAP);
                                int pn = data.getInt("pn");
                                int n = data.getInt("n");

                                RatchetHeader header = new RatchetHeader(dhPubBytes, pn, n);
                                header.ciphertext = cipherBytes;

                                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(dhPubBytes);
                                KeyFactory kf = KeyFactory.getInstance("X25519", "BC");

                                Log.d(TAG, "-> Attempting Decryption... Ratchet Turn (Nr): " + n);
                                byte[] decrypted = sessionRatchet.decrypt(header, kf.generatePublic(keySpec));
                                String decryptedText = new String(decrypted, StandardCharsets.UTF_8);
                                Log.d(TAG, "-> SUCCESS: Message Decrypted Successfully.\n");

                                // 🔥 SAVE ENCRYPTION STATE AFTER DECRYPTING 🔥
                                saveSessionState();

                                ChatMessage chatMessage = new ChatMessage();
                                chatMessage.senderId = receiverUser.id;
                                chatMessage.message = decryptedText;
                                chatMessage.dateTime = getReadableDateTime(new Date());

                                localDB.saveMessage(receiverUser.id, preferenceManager.getString(Constants.KEY_USER_ID), decryptedText, chatMessage.dateTime);

                                chatMessages.add(chatMessage);
                                chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                                binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);

                            } catch (Exception decryptEx) {
                                Log.e(TAG, "Decryption failed: " + decryptEx.getMessage());
                            }
                        } else {
                            Log.e(TAG, "Ratchet state is null! SK Mismatch or State Lost.");

                            ChatMessage fallbackMsg = new ChatMessage();
                            fallbackMsg.senderId = receiverUser.id;
                            fallbackMsg.message = "🔒 [Encrypted Message - Key Missing]";
                            fallbackMsg.dateTime = getReadableDateTime(new Date());

                            chatMessages.add(fallbackMsg);
                            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);
                        }

                        JSONObject readObj = new JSONObject();
                        readObj.put("sender", sender);
                        readObj.put("receiver", preferenceManager.getString(Constants.KEY_PHONE));
                        mSocket.emit("message_read", readObj);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }

    private void loadReceiverDetails() {
        receiverUser = (User) getIntent().getSerializableExtra(Constants.KEY_USER);
        binding.textName.setText(receiverUser.name);
    }

    private String getReadableDateTime(Date date) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date);
    }

    private Bitmap getBitmapFromEncodedString(String encodedImage) {
        if (encodedImage == null) return null;
        byte[] bytes = Base64.decode(encodedImage, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    // Helper method for logging hex strings
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
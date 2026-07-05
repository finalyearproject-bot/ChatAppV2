package com.example.chatapp.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.adapters.UsersAdapter;
import com.example.chatapp.crypto.CryptoEngine;
import com.example.chatapp.listeners.UserListener;
import com.example.chatapp.models.PreKeyBundle;
import com.example.chatapp.models.User;
import com.example.chatapp.databinding.ActivityMainBinding;
import com.example.chatapp.network.ApiClient;
import com.example.chatapp.network.ApiService;
import com.example.chatapp.utilities.Constants;
import com.example.chatapp.utilities.PreferenceManager;
import com.example.chatapp.utilities.SocketHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.Security;
import java.util.List;

import io.socket.client.Socket;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements UserListener {

    private ActivityMainBinding binding;
    private PreferenceManager preferenceManager;
    private Socket mSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔥 CRITICAL CRYPTO FIX: Inject full BouncyCastle before anything else runs 🔥
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferenceManager = new PreferenceManager(getApplicationContext());

        loadUserDetails();
        initSocket();
        getUsers();

        setupAndUploadKeys();

        binding.imageSignOut.setOnClickListener(v -> signOut());
    }

    private void setupAndUploadKeys() {
        if (preferenceManager.getString("my_identity_private") == null) {
            try {
                KeyPair myIdentityKey = CryptoEngine.generateX25519KeyPair();
                KeyPair mySignedPreKey = CryptoEngine.generateX25519KeyPair();

                String idPriv = Base64.encodeToString(myIdentityKey.getPrivate().getEncoded(), Base64.NO_WRAP);
                String spkPriv = Base64.encodeToString(mySignedPreKey.getPrivate().getEncoded(), Base64.NO_WRAP);
                String idPub = Base64.encodeToString(myIdentityKey.getPublic().getEncoded(), Base64.NO_WRAP);
                String spkPub = Base64.encodeToString(mySignedPreKey.getPublic().getEncoded(), Base64.NO_WRAP);

                preferenceManager.putString("my_identity_private", idPriv);
                preferenceManager.putString("my_signed_prekey_private", spkPriv);
                preferenceManager.putString("my_identity_public", idPub);
                preferenceManager.putString("my_signed_prekey_public", spkPub);

                PreKeyBundle myBundle = new PreKeyBundle();
                myBundle.phone = preferenceManager.getString(Constants.KEY_PHONE);
                myBundle.identityPublic = idPub;
                myBundle.signedPreKeyPublic = spkPub;

                ApiService apiService = ApiClient.getClient().create(ApiService.class);
                apiService.uploadPreKeyBundle(myBundle).enqueue(new Callback<Void>() {
                    @Override
                    public void onResponse(Call<Void> call, Response<Void> response) {
                        Log.d("CRYPTO", "Keys uploaded successfully!");
                    }
                    @Override
                    public void onFailure(Call<Void> call, Throwable t) {
                        Log.e("CRYPTO", "Key upload failed");
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void getUsers() {
        loading(true);
        ApiService apiService = ApiClient.getClient().create(ApiService.class);
        String currentUserId = preferenceManager.getString(Constants.KEY_USER_ID);

        Call<List<User>> call = apiService.getUsers(currentUserId);
        call.enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                loading(false);
                if (response.isSuccessful() && response.body() != null) {
                    List<User> users = response.body();
                    if (users.size() > 0) {
                        UsersAdapter usersAdapter = new UsersAdapter(users, MainActivity.this);
                        binding.usersRecyclerView.setAdapter(usersAdapter);
                        binding.usersRecyclerView.setVisibility(View.VISIBLE);
                    } else {
                        showErrorMessage();
                    }
                } else {
                    showErrorMessage();
                }
            }
            @Override
            public void onFailure(Call<List<User>> call, Throwable t) {
                loading(false);
                showErrorMessage();
            }
        });
    }

    private void showErrorMessage() {
        binding.textErrorMessage.setText("No users available");
        binding.textErrorMessage.setVisibility(View.VISIBLE);
    }

    private void loading(Boolean isLoading) {
        if (isLoading) {
            binding.progressBar.setVisibility(View.VISIBLE);
        } else {
            binding.progressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void loadUserDetails() {
        binding.textName.setText(preferenceManager.getString(Constants.KEY_NAME));
        String base64Image = preferenceManager.getString(Constants.KEY_IMAGE);
        if (base64Image != null) {
            byte[] bytes = Base64.decode(base64Image, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            binding.imageProfile.setImageBitmap(bitmap);
        }
    }

    private void initSocket() {
        SocketHandler.setSocket();
        mSocket = SocketHandler.getSocket();

        mSocket.on(Socket.EVENT_CONNECT, args -> {
            runOnUiThread(() -> {
                try {
                    String phone = preferenceManager.getString(Constants.KEY_PHONE);
                    if (phone != null) {
                        JSONObject data = new JSONObject();
                        data.put("phone", phone);
                        mSocket.emit("register", data);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            });
        });
        mSocket.connect();
    }

    private void signOut() {
        Toast.makeText(this, "Signing out...", Toast.LENGTH_SHORT).show();
        if (mSocket != null) mSocket.disconnect();
        preferenceManager.clear();
        startActivity(new Intent(getApplicationContext(), SignInActivity.class));
        finish();
    }

    @Override
    public void onUserClicked(User user) {
        Intent intent = new Intent(getApplicationContext(), ChatActivity.class);
        intent.putExtra(Constants.KEY_USER, user);
        startActivity(intent);
    }
}
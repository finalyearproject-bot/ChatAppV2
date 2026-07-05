package com.example.chatapp.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.databinding.ActivitySignInBinding;
import com.example.chatapp.models.SignInRequest;
import com.example.chatapp.models.SignInResponse;
import com.example.chatapp.network.ApiClient;
import com.example.chatapp.network.ApiService;
import com.example.chatapp.utilities.Constants;
import com.example.chatapp.utilities.PreferenceManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SignInActivity extends AppCompatActivity {

    private ActivitySignInBinding binding;
    private PreferenceManager preferenceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferenceManager = new PreferenceManager(getApplicationContext());

        if (preferenceManager.getBoolean(Constants.KEY_IS_SIGNED_IN)) {
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            startActivity(intent);
            finish();
        }

        binding = ActivitySignInBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setListeners();
    }

    private void setListeners() {
        binding.textCreateNewAccount.setOnClickListener(v ->
                startActivity(new Intent(getApplicationContext(), SignUpActivity.class)));

        binding.buttonSignIn.setOnClickListener(v -> {
            if (isValidSignInDetails()) {
                signIn();
            }
        });
    }

    private void signIn() {
        loading(true);

        // 👇 Get Phone instead of Email
        String phone = binding.inputPhone.getText().toString().trim();
        String password = binding.inputPassword.getText().toString().trim();

        ApiService apiService = ApiClient.getClient().create(ApiService.class);
        SignInRequest requestBody = new SignInRequest(phone, password); // Updated Model

        Call<SignInResponse> call = apiService.signIn(requestBody);

        call.enqueue(new Callback<SignInResponse>() {
            @Override
            public void onResponse(Call<SignInResponse> call, Response<SignInResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().status != null && response.body().status) {

                        preferenceManager.putBoolean(Constants.KEY_IS_SIGNED_IN, true);
                        preferenceManager.putString(Constants.KEY_USER_ID, response.body().userId);
                        preferenceManager.putString(Constants.KEY_NAME, response.body().name);
                        preferenceManager.putString(Constants.KEY_IMAGE, response.body().image);

                        // ⚠️ SAVE PHONE NUMBER FOR CHAT SOCKET
                        preferenceManager.putString(Constants.KEY_PHONE, phone);

                        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    } else {
                        loading(false);
                        showToast(response.body().message != null ? response.body().message : "Login Failed");
                    }
                } else {
                    loading(false);
                    showToast("Error: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<SignInResponse> call, Throwable t) {
                loading(false);
                showToast("Connection failed: " + t.getMessage());
            }
        });
    }

    private void loading(Boolean isLoading) {
        if (isLoading) {
            binding.buttonSignIn.setVisibility(View.INVISIBLE);
            binding.progressBar.setVisibility(View.VISIBLE);
        } else {
            binding.progressBar.setVisibility(View.INVISIBLE);
            binding.buttonSignIn.setVisibility(View.VISIBLE);
        }
    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    private Boolean isValidSignInDetails() {
        if (binding.inputPhone.getText().toString().trim().isEmpty()) {
            showToast("Enter phone number");
            return false;
        } else if (binding.inputPassword.getText().toString().trim().isEmpty()) {
            showToast("Enter password");
            return false;
        } else {
            return true;
        }
    }
}
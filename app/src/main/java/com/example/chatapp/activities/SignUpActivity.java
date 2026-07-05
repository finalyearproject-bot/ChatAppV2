package com.example.chatapp.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.databinding.ActivitySignUpBinding;
import com.example.chatapp.models.SignUpRequest;
import com.example.chatapp.models.SignUpResponse;
import com.example.chatapp.network.ApiClient;
import com.example.chatapp.network.ApiService;
import com.example.chatapp.utilities.Constants;
import com.example.chatapp.utilities.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SignUpActivity extends AppCompatActivity {

    private ActivitySignUpBinding binding;
    private PreferenceManager preferenceManager;
    private String encodedImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySignUpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferenceManager = new PreferenceManager(getApplicationContext());
        setListeners();
    }

    private void setListeners() {
        binding.textSignIn.setOnClickListener(v -> onBackPressed());

        binding.buttonSignUp.setOnClickListener(v -> {
            if (isValidSignUpDetails()) {
                signUp();
            }
        });

        // 👇 UPDATED: Launch the modern Photo Picker
        binding.layoutImage.setOnClickListener(v -> {
            pickImage.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
    }

    private void signUp() {
        loading(true);
        ApiService apiService = ApiClient.getClient().create(ApiService.class);

        // Using Phone instead of Email
        String phone = binding.inputPhone.getText().toString().trim();

        SignUpRequest requestBody = new SignUpRequest(
                binding.inputName.getText().toString(),
                phone,
                binding.inputPassword.getText().toString(),
                encodedImage
        );

        Call<SignUpResponse> call = apiService.signUp(requestBody);

        call.enqueue(new Callback<SignUpResponse>() {
            @Override
            public void onResponse(Call<SignUpResponse> call, Response<SignUpResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    loading(false);

                    if (response.body().status != null && response.body().status) {
                        preferenceManager.putBoolean(Constants.KEY_IS_SIGNED_IN, true);
                        preferenceManager.putString(Constants.KEY_USER_ID, response.body().userId);
                        preferenceManager.putString(Constants.KEY_NAME, binding.inputName.getText().toString());
                        preferenceManager.putString(Constants.KEY_IMAGE, encodedImage);

                        // SAVE PHONE NUMBER FOR CHAT SOCKET
                        preferenceManager.putString(Constants.KEY_PHONE, phone);

                        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    } else {
                        showToast(response.body().message != null ? response.body().message : "Sign Up Failed");
                    }
                } else {
                    loading(false);
                    showToast("Server Error: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<SignUpResponse> call, Throwable t) {
                loading(false);
                showToast("Error: " + t.getMessage());
            }
        });
    }

    private String encodeImage(Bitmap bitmap) {
        int previewWidth = 150;
        int previewHeight = bitmap.getHeight() * previewWidth / bitmap.getWidth();
        Bitmap previewBitmap = Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, false);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        previewBitmap.compress(Bitmap.CompressFormat.JPEG, 50, byteArrayOutputStream);
        byte[] bytes = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(bytes, Base64.DEFAULT);
    }

    // 👇 UPDATED: Replaced StartActivityForResult with PickVisualMedia
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            uri -> {
                if (uri != null) {
                    try {
                        InputStream inputStream = getContentResolver().openInputStream(uri);
                        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                        binding.imageProfile.setImageBitmap(bitmap);
                        binding.textAddImage.setVisibility(View.GONE);
                        encodedImage = encodeImage(bitmap);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        showToast("Failed to load image");
                    }
                }
            }
    );

    private Boolean isValidSignUpDetails() {
        if (encodedImage == null) {
            showToast("Select profile image");
            return false;
        } else if (binding.inputName.getText().toString().trim().isEmpty()) {
            showToast("Enter name");
            return false;
        } else if (binding.inputPhone.getText().toString().trim().isEmpty()) {
            showToast("Enter phone number");
            return false;
        } else if (binding.inputPassword.getText().toString().trim().isEmpty()) {
            showToast("Enter password");
            return false;
        } else if (binding.inputConfirmPassword.getText().toString().trim().isEmpty()) {
            showToast("Confirm your password");
            return false;
        } else if (!binding.inputPassword.getText().toString().equals(binding.inputConfirmPassword.getText().toString())) {
            showToast("Password & confirm password must be same");
            return false;
        } else {
            return true;
        }
    }

    private void loading(Boolean isLoading) {
        if (isLoading) {
            binding.buttonSignUp.setVisibility(View.INVISIBLE);
            binding.progressBar.setVisibility(View.VISIBLE);
        } else {
            binding.progressBar.setVisibility(View.INVISIBLE);
            binding.buttonSignUp.setVisibility(View.VISIBLE);
        }
    }

    public void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
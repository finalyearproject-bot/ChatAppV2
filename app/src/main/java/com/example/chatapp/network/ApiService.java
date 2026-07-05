package com.example.chatapp.network;

import com.example.chatapp.models.ChatMessage;
import com.example.chatapp.models.SignInRequest;
import com.example.chatapp.models.SignInResponse;
import com.example.chatapp.models.SignUpRequest;
import com.example.chatapp.models.SignUpResponse;
import com.example.chatapp.models.User; // Import User

import com.example.chatapp.models.PreKeyBundle;

import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface ApiService {

    @POST("signup")
    Call<SignUpResponse> signUp(@Body SignUpRequest signUpRequest);

    @POST("login")
    Call<SignInResponse> signIn(@Body SignInRequest signInRequest);

    @GET("messages")
    Call<List<ChatMessage>> getChatHistory(
            @Query("sender") String sender,
            @Query("receiver") String receiver
    );

    // 👇 ADD THIS MISSING METHOD 👇
    @GET("users")
    Call<List<User>> getUsers(@Query("userId") String userId);

    // Add to your existing ApiService.java
    @GET("keys")
    Call<PreKeyBundle> getPreKeyBundle(@Query("phone") String phone);

    @POST("keys/upload")
    Call<Void> uploadPreKeyBundle(@Body PreKeyBundle bundle);
}
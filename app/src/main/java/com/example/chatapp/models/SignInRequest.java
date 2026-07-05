package com.example.chatapp.models;

public class SignInRequest {
    public String phone; // Changed
    public String password;
    public SignInRequest(String phone, String password) {
        this.phone = phone;
        this.password = password;
    }
}

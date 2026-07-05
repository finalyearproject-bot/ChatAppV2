package com.example.chatapp.models;

public class SignUpRequest {
    public String name;
    public String phone; // Changed
    public String password;
    public String image;
    public SignUpRequest(String name, String phone, String password, String image) {
        this.name = name;
        this.phone = phone;
        this.password = password;
        this.image = image;
    }
}
package com.example.chatapp.models;

import com.google.gson.annotations.SerializedName;

public class PreKeyBundle {

    // This field allows the server to know which user the keys belong to
    @SerializedName("phone")
    public String phone;

    @SerializedName("identity_public")
    public String identityPublic;

    @SerializedName("signed_prekey_public")
    public String signedPreKeyPublic;

    @SerializedName("signature")
    public String signature;

    @SerializedName("one_time_prekey_public")
    public String oneTimePreKeyPublic;
}
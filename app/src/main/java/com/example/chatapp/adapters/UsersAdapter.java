package com.example.chatapp.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatapp.databinding.ItemContainerUserBinding;
import com.example.chatapp.listeners.UserListener;
import com.example.chatapp.models.User;

import java.util.List;

public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.UserViewHolder> {

    private final List<User> users;
    private final UserListener userListener;

    public UsersAdapter(List<User> users, UserListener userListener) {
        this.users = users;
        this.userListener = userListener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemContainerUserBinding itemContainerUserBinding = ItemContainerUserBinding.inflate(
                LayoutInflater.from(parent.getContext()),
                parent,
                false
        );
        return new UserViewHolder(itemContainerUserBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        holder.setUserData(users.get(position));
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    class UserViewHolder extends RecyclerView.ViewHolder {

        ItemContainerUserBinding binding;

        UserViewHolder(ItemContainerUserBinding itemContainerUserBinding) {
            super(itemContainerUserBinding.getRoot());
            binding = itemContainerUserBinding;
        }

        void setUserData(User user) {
            binding.textName.setText(user.name);
            // 👇 CHANGE: Using phone instead of email here
            binding.textEmail.setText(user.phone);

            // Safe image loading
            Bitmap profilePic = getUserImage(user.image);
            if (profilePic != null) {
                binding.imageProfile.setImageBitmap(profilePic);
            } else {
                // Optional: Clear the image or set a default avatar if the string was null
                binding.imageProfile.setImageBitmap(null);
                // binding.imageProfile.setImageResource(R.drawable.your_default_avatar);
            }

            binding.getRoot().setOnClickListener(v -> userListener.onUserClicked(user));
        }
    }

    private Bitmap getUserImage(String encodedImage) {
        // Prevent NullPointerException by checking if the string exists
        if (encodedImage == null || encodedImage.trim().isEmpty()) {
            return null;
        }

        try {
            byte[] bytes = Base64.decode(encodedImage, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return null;
        }
    }
}
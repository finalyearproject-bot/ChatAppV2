package com.example.chatapp.utilities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.example.chatapp.models.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public class LocalDBManager extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "ChatAppLocal.db";
    private static final int DATABASE_VERSION = 1;

    public LocalDBManager(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "senderId TEXT, " +
                "receiverId TEXT, " +
                "message TEXT, " +
                "dateTime TEXT)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    // Save a decrypted message locally
    public void saveMessage(String senderId, String receiverId, String message, String dateTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("senderId", senderId);
        values.put("receiverId", receiverId);
        values.put("message", message);
        values.put("dateTime", dateTime);
        db.insert("messages", null, values);
        db.close();
    }

    // Fetch messages only for this specific chat
    public List<ChatMessage> getLocalChatHistory(String myId, String peerId) {
        List<ChatMessage> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT * FROM messages WHERE (senderId=? AND receiverId=?) OR (senderId=? AND receiverId=?)";
        Cursor cursor = db.rawQuery(query, new String[]{myId, peerId, peerId, myId});

        if (cursor.moveToFirst()) {
            do {
                ChatMessage msg = new ChatMessage();
                msg.senderId = cursor.getString(1);
                // We ignore receiverId for the UI
                msg.message = cursor.getString(3);
                msg.dateTime = cursor.getString(4);
                list.add(msg);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }
}
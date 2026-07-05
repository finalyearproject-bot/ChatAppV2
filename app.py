from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit, join_room
from pymongo import MongoClient
import certifi
import socket
from datetime import datetime
from bson.objectid import ObjectId
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)
app.config['SECRET_KEY'] = 'my_secret_key'
# Using threading for simplicity; use 'eventlet' or 'gevent' for production
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# --- DATABASE CONNECTION ---
# Tip: In production, move MONGO_URI to an environment variable (.env)
MONGO_URI = "mongodb+srv://Intership:rohan2004@cluster0.6rqtgnz.mongodb.net/"
client = MongoClient(MONGO_URI, tlsCAFile=certifi.where())
db = client.chat_database

print("\n" + "="*50)
print("✅ MONGODB CONNECTED SUCCESSFULLY")

# RESET GHOST SESSIONS ON STARTUP
# Clears old session data in case the server was unexpectedly restarted
db.users.update_many({}, {"$set": {"online": False, "sid": None}})
print("🧹 Cleared leftover online sessions")
print("="*50 + "\n")


# --- HTTP API ROUTES ---

@app.route("/", methods=["GET"])
def health_check():
    """Fixes the 404 GET / error and acts as a server ping"""
    return jsonify({"status": True, "message": "Chat App Backend is running smoothly!"}), 200

@app.route("/signup", methods=["POST"])
def api_signup():
    try:
        data = request.get_json()
        phone = data.get("phone")
        password = data.get("password")
        name = data.get("name")
        image = data.get("image")
        
        if not phone or not password:
            return jsonify({"status": False, "message": "Phone and password are required"}), 400
        
        if db.users.find_one({"phone": phone}):
            return jsonify({"status": False, "message": "User already exists"}), 409
            
        hashed_password = generate_password_hash(password)
        user_id = db.users.insert_one({
            "phone": phone,
            "password": hashed_password,
            "name": name,
            "image": image,
            "online": False,
            "sid": None
        }).inserted_id
        
        print(f"🆕 NEW USER SIGNED UP: {phone}")
        return jsonify({"status": True, "message": "User created", "userId": str(user_id), "name": name}), 201
    
    except Exception as e:
        print(f"❌ SIGNUP ERROR: {e}")
        return jsonify({"status": False, "message": "Internal Server Error"}), 500

@app.route("/login", methods=["POST"])
def api_login():
    try:
        data = request.get_json()
        phone = data.get("phone")
        password = data.get("password")
        
        if not phone or not password:
            return jsonify({"status": False, "message": "Phone and password are required"}), 400
            
        user = db.users.find_one({"phone": phone})
        
        if user and check_password_hash(user['password'], password):
            # Reset socket ID on fresh login to prevent ghost sessions
            db.users.update_one({"_id": user["_id"]}, {"$set": {"online": False, "sid": None}})
            
            print(f"🔑 LOGIN SUCCESS: {phone}")
            return jsonify({
                "status": True,
                "userId": str(user["_id"]),
                "name": user.get("name"),
                "image": user.get("image")
            }), 200
            
        print(f"❌ LOGIN FAILED: {phone}")
        return jsonify({"status": False, "message": "Invalid Credentials"}), 401
        
    except Exception as e:
        print(f"❌ LOGIN ERROR: {e}")
        return jsonify({"status": False, "message": "Internal Server Error"}), 500

@app.route("/users", methods=["GET"])
def get_users():
    try:
        current_user_id = request.args.get("userId")
        
        # Ensure the userId is a valid ObjectId before querying
        if current_user_id and ObjectId.is_valid(current_user_id):
            query = {"_id": {"$ne": ObjectId(current_user_id)}}
        else:
            query = {}
        
        users = []
        for user in db.users.find(query):
            users.append({
                "id": str(user["_id"]),
                "name": user.get("name"),
                "phone": user.get("phone"),
                "image": user.get("image"),
                "online": user.get("online", False)
            })
        return jsonify(users), 200
        
    except Exception as e:
        print(f"❌ FETCH USERS ERROR: {e}")
        return jsonify([]), 500

@app.route("/messages", methods=["GET"])
def get_messages():
    try:
        sender = request.args.get("sender")
        receiver = request.args.get("receiver")
        
        if not sender or not receiver:
            return jsonify({"status": False, "message": "Sender and receiver required"}), 400
            
        messages = list(db.messages.find({
            "$or": [
                {"sender": sender, "receiver": receiver},
                {"sender": receiver, "receiver": sender}
            ]
        }).sort("timestamp", 1))

        output = []
        for msg in messages:
            output.append({
                "sender": msg["sender"],
                "receiver": msg["receiver"],
                "message": msg["content"],
                "dateTime": msg["timestamp"].strftime("%I:%M %p"),
                "status": msg.get("status", 1)
            })
        return jsonify(output), 200
        
    except Exception as e:
        print(f"❌ FETCH MESSAGES ERROR: {e}")
        return jsonify([]), 500


# --- SOCKET.IO EVENTS ---

@socketio.on("connect")
def handle_connect():
    print(f"🔌 NEW CONNECTION: SID [{request.sid}]")

@socketio.on("disconnect")
def handle_disconnect():
    user = db.users.find_one({"sid": request.sid})
    if user:
        phone = user["phone"]
        db.users.update_one({"_id": user["_id"]}, {"$set": {"online": False, "sid": None}})
        print(f"\n🔴 USER DISCONNECTED: {phone}")
        print(f"   SID {request.sid} cleared.")
        print("-" * 40)
    else:
        print(f"🔌 UNKNOWN SID DISCONNECTED: {request.sid}")

@socketio.on("register")
def handle_register(data):
    phone = data.get("phone")
    if phone:
        join_room(phone) 
        db.users.update_one(
            {"phone": phone}, 
            {"$set": {"online": True, "sid": request.sid}}
        )

        print(f"\n🟢 USER ONLINE: {phone}")
        print(f"   Room Joined | SID: {request.sid}")
        
        # Delivery logic for missed messages
        pending_msgs = list(db.messages.find({"receiver": phone, "status": 1}))
        if pending_msgs:
            print(f"   📦 Delivering {len(pending_msgs)} pending messages...")
            for msg in pending_msgs:
                emit("receive_message", {
                    "sender": msg["sender"], 
                    "message": msg["content"],
                    "dateTime": msg["timestamp"].strftime("%I:%M %p")
                }, room=phone)
                
                # Notify original sender it's now delivered
                emit("message_status", {"status": 2}, room=msg["sender"])
                db.messages.update_one({"_id": msg["_id"]}, {"$set": {"status": 2}})
        else:
            print("   No pending messages.")
        print("-" * 40)

@socketio.on("send_message")
def handle_message(data):
    sender = data.get("sender")     # Sender Phone
    receiver = data.get("receiver") # Receiver Phone
    content = data.get("message")
    timestamp = datetime.now()
    
    # 1. Save to Database
    msg_id = db.messages.insert_one({
        "sender": sender, 
        "receiver": receiver, 
        "content": content, 
        "timestamp": timestamp, 
        "status": 1 # Sent
    }).inserted_id

    print(f"\n💬 MESSAGE TRANSACTION")
    print(f"   FROM: {sender}")
    print(f"   TO:   {receiver}")

    # 2. Check if Receiver is Online
    receiver_user = db.users.find_one({"phone": receiver})
    
    if receiver_user and receiver_user.get("online"):
        # Real-time Delivery
        emit("receive_message", {
            "sender": sender, 
            "message": content,
            "dateTime": timestamp.strftime("%I:%M %p")
        }, room=receiver)
        
        # Update status to Delivered (2)
        emit("message_status", {"status": 2}, room=sender)
        db.messages.update_one({"_id": msg_id}, {"$set": {"status": 2}})
        print(f"   ✅ STATUS: Delivered (Online)")
    else:
        # Offline logic
        emit("message_status", {"status": 1}, room=sender)
        print(f"   ☁️  STATUS: Saved to DB (Offline)")
    
    print("-" * 40)

@socketio.on("message_read")
def handle_read(data):
    sender = data.get("sender")     
    receiver = data.get("receiver") 
    
    result = db.messages.update_many(
        {"sender": sender, "receiver": receiver, "status": {"$lt": 3}},
        {"$set": {"status": 3}}
    )
    
    if result.modified_count > 0:
        print(f"\n✅ MESSAGES READ")
        print(f"   Reader: {receiver}")
        print(f"   Sender: {sender} notified (Blue Ticks)")
        emit("message_status", {"status": 3}, room=sender)
        print("-" * 40)


# --- RUN SERVER ---

if __name__ == "__main__":
    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)
    print(f"🚀 SERVER STARTING...")
    print(f"🔗 LOCAL URL: http://{local_ip}:8080")
    print(f"🔗 EXTERNAL:  http://0.0.0.0:8080")
    print("="*50 + "\n")
    socketio.run(app, host="0.0.0.0", port=8080, debug=True, use_reloader=False)

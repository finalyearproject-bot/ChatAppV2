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
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# --- DATABASE CONNECTION ---
MONGO_URI = "" # Mongo DB URL
client = MongoClient(MONGO_URI, tlsCAFile=certifi.where())
db = client.chat_database

print("\n" + "="*50)
print("MONGODB CONNECTED SUCCESSFULLY")
db.users.update_many({}, {"$set": {"online": False, "sid": None}})
print("🧹 Cleared leftover online sessions")
print("="*50 + "\n")


# --- HTTP API ROUTES ---

@app.route("/", methods=["GET"])
def health_check():
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
            "sid": None,
            "identityPublic": None,       
            "signedPreKeyPublic": None    
        }).inserted_id
        
        print(f" NEW USER SIGNED UP: {phone}")
        return jsonify({"status": True, "message": "User created", "userId": str(user_id), "name": name}), 201
    except Exception as e:
        return jsonify({"status": False, "message": "Internal Server Error"}), 500

@app.route("/login", methods=["POST"])
def api_login():
    try:
        data = request.get_json()
        phone = data.get("phone")
        password = data.get("password")
        
        user = db.users.find_one({"phone": phone})
        
        if user and check_password_hash(user['password'], password):
            db.users.update_one({"_id": user["_id"]}, {"$set": {"online": False, "sid": None}})
            print(f" LOGIN SUCCESS: {phone}")
            return jsonify({
                "status": True,
                "userId": str(user["_id"]),
                "name": user.get("name"),
                "image": user.get("image")
            }), 200
            
        return jsonify({"status": False, "message": "Invalid Credentials"}), 401
    except Exception as e:
        return jsonify({"status": False, "message": "Internal Server Error"}), 500

@app.route("/users", methods=["GET"])
def get_users():
    try:
        current_user_id = request.args.get("userId")
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
        return jsonify([]), 500

@app.route("/messages", methods=["GET"])
def get_messages():
    try:
        sender = request.args.get("sender")
        receiver = request.args.get("receiver")
        
        messages = list(db.messages.find({
            "$or": [
                {"sender": sender, "receiver": receiver},
                {"sender": receiver, "receiver": sender}
            ]
        }).sort("timestamp", 1))

        output = []
        for msg in messages:
            # Dynamically return all keys stored (so E2EE headers survive DB retrieval)
            msg_data = {k: v for k, v in msg.items() if k not in ["_id", "timestamp"]}
            msg_data["dateTime"] = msg["timestamp"].strftime("%I:%M %p")
            output.append(msg_data)
            
        return jsonify(output), 200
    except Exception as e:
        return jsonify([]), 500


# --- X3DH KEY REGISTRY ENDPOINTS ---

@app.route("/keys/upload", methods=["POST"])
def upload_keys():
    try:
        data = request.get_json(force=True) 
        phone = data.get("phone")
        
        identity_public = data.get("identityPublic") or data.get("identityKey") or data.get("identity_public")
        signed_prekey_public = data.get("signedPreKeyPublic") or data.get("signedPreKey") or data.get("signed_prekey_public")

        if not phone or not identity_public or not signed_prekey_public:
            return jsonify({"status": False, "message": "Missing key data"}), 400

        db.users.update_one(
            {"phone": phone},
            {"$set": {
                "identityPublic": identity_public,
                "signedPreKeyPublic": signed_prekey_public
            }}
        )
        print(f"\n RECEIVED KEY UPLOAD FOR: {phone} (Keys securely hidden)")
        return jsonify({"status": True, "message": "Keys uploaded successfully"}), 200
    except Exception as e:
        return jsonify({"status": False, "message": "Internal Server Error"}), 500

@app.route("/keys", methods=["GET"])
def get_keys():
    try:
        phone = request.args.get("phone") 
        user = db.users.find_one({"phone": phone})
        if user and user.get("identityPublic") and user.get("signedPreKeyPublic"):
            return jsonify({
                "phone": user["phone"],
                "identityPublic": user["identityPublic"],
                "signedPreKeyPublic": user["signedPreKeyPublic"],
                "identity_public": user["identityPublic"],
                "signed_prekey_public": user["signedPreKeyPublic"]
            }), 200
        return jsonify({"status": False, "message": "Keys not found"}), 404
    except Exception as e:
        return jsonify({"status": False, "message": "Internal Server Error"}), 500


# --- SOCKET.IO EVENTS (BLIND RELAY) ---

@socketio.on("connect")
def handle_connect():
    print(f" NEW CONNECTION: SID [{request.sid}]")

@socketio.on("disconnect")
def handle_disconnect():
    user = db.users.find_one({"sid": request.sid})
    if user:
        phone = user["phone"]
        db.users.update_one({"_id": user["_id"]}, {"$set": {"online": False, "sid": None}})
        print(f"\n USER DISCONNECTED: {phone}")
        print("-" * 40)

@socketio.on("register")
def handle_register(data):
    phone = data.get("phone")
    if phone:
        join_room(phone) 
        db.users.update_one({"phone": phone}, {"$set": {"online": True, "sid": request.sid}})
        print(f"\n USER ONLINE: {phone}")
        
        # Delivery logic for missed E2EE messages
        pending_msgs = list(db.messages.find({"receiver": phone, "status": 1}))
        if pending_msgs:
            print(f"   Delivering {len(pending_msgs)} pending encrypted payloads...")
            for msg in pending_msgs:
                # Forward everything exactly as it was stored
                delivery_payload = {k: v for k, v in msg.items() if k not in ["_id", "timestamp"]}
                delivery_payload["dateTime"] = msg["timestamp"].strftime("%I:%M %p")
                
                emit("receive_message", delivery_payload, room=phone)
                emit("message_status", {"status": 2}, room=msg["sender"])
                db.messages.update_one({"_id": msg["_id"]}, {"$set": {"status": 2}})
        print("-" * 40)

@socketio.on("send_message")
def handle_message(data):
    """BLIND RELAY: Forwards the entire E2EE payload without stripping keys"""
    sender = data.get("sender")     
    receiver = data.get("receiver") 
    timestamp = datetime.now()
    
    # 1. Save ENTIRE dictionary to Database so no E2EE params are lost
    db_payload = data.copy()
    db_payload["timestamp"] = timestamp
    db_payload["status"] = 1
    msg_id = db.messages.insert_one(db_payload).inserted_id

    print(f"\n ENCRYPTED TRANSACTION")
    print(f"   FROM: {sender}")
    print(f"   TO:   {receiver}")

    # 2. Forward ENTIRE dictionary to receiver
    receiver_user = db.users.find_one({"phone": receiver})
    
    if receiver_user and receiver_user.get("online"):
        emit_payload = data.copy()
        emit_payload["dateTime"] = timestamp.strftime("%I:%M %p")
        
        emit("receive_message", emit_payload, room=receiver)
        emit("message_status", {"status": 2}, room=sender)
        db.messages.update_one({"_id": msg_id}, {"$set": {"status": 2}})
        print(f"  STATUS: Ciphertext Delivered (Online)")
    else:
        emit("message_status", {"status": 1}, room=sender)
        print(f"   STATUS: Ciphertext Queued in DB (Offline)")
    
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
        emit("message_status", {"status": 3}, room=sender)


if __name__ == "__main__":
    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)
    print(f" SERVER STARTING...")
    print(f" LOCAL URL: http://{local_ip}:8080")
    print(f" EXTERNAL:  http://0.0.0.0:8080")
    print("="*50 + "\n")
    socketio.run(app, host="0.0.0.0", port=8080, debug=True, use_reloader=False)

```markdown
# Secure E2EE Chat Application (v2.0)

A real-time, privacy-focused messaging system featuring a Python (Flask + Socket.IO) backend and a native Android client. 

> **🔒 Security & Encryption (v2.0 Update):** 
> This version introduces robust End-to-End Encryption (E2EE). It implements the **X3DH (Extended Triple Diffie-Hellman)** key agreement protocol and the **Double Ratchet** algorithm. The Python backend acts strictly as a **Blind Relay** and Key Server—it stores and forwards encrypted ciphertext payloads and public keys, but cannot decrypt the actual message contents.

---

## ⚙️ Backend Setup (Python Server)

The backend is completely contained within the `app.py` file and is responsible for user authentication, storing public keys for X3DH, and forwarding encrypted Socket.IO payloads.

### 1. Requirements & Installation

The server requires **Python 3.x**. Below is the breakdown of the required libraries for the backend:

| Dependency | Purpose in this Project |
| :--- | :--- |
| **Flask** | The core micro web framework handling the HTTP API routes and Key Server endpoints. |
| **Flask-SocketIO** | Enables the real-time, bidirectional WebSocket communication for the blind relay. |
| **PyMongo** | The official MongoDB driver used to read and write database records. |
| **Certifi** | Provides TLS/SSL certificates to securely connect to MongoDB Atlas. |
| **Werkzeug** | Utility library used specifically for secure password hashing. |

Install all dependencies in a single line using your terminal:

```bash
pip install Flask Flask-SocketIO pymongo certifi werkzeug

```

### 2. MongoDB Configuration

Open `app.py` and ensure your MongoDB connection string is correctly placed in the `MONGO_URI` variable:

```python
MONGO_URI = "mongodb+srv://<username>:<password>@your-cluster.mongodb.net/"

```

### 3. Running the Server

Start the backend server by running:

```bash
python app.py

```

The server will run on port `8080` (e.g., `http://0.0.0.0:8080`).

---

## 📱 App Side Architecture (Android Client)

The frontend is a native Android application. In Version 2.0, cryptographic operations have been heavily integrated into the client.

### Android Dependencies (Gradle)

To handle the complex cryptography required for X3DH and the Double Ratchet (such as Curve25519, AES-GCM, and HKDF), the client utilizes the **Bouncy Castle** Java library. Ensure this is added to your `app/build.gradle` file:

```gradle
dependencies {
    // Standard UI, Retrofit, and Socket.IO dependencies...
    
    // Bouncy Castle for Cryptographic Operations 
    implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
}

```

### 🔎 Transparent Cryptographic Logging (Logcat)

For development and debugging purposes, the complete cryptographic lifecycle is fully visible in **Android Studio Logcat**. You can monitor the protocol's execution in real-time by filtering for specific tags (e.g., `CRYPTO_PROTOCOL`).

The logs output detailed steps powered by the Bouncy Castle library, including:

* **Key Generation:** Ephemeral Key Pair (EKA) generation using Curve25519.
* **X3DH Handshake:** The calculation of DH1, DH2, and DH3, followed by the HKDF-SHA256 derivation of the Shared Key (SK).
* **Double Ratchet:** Turn tracking, Ratchet state advancements, and message encryption/decryption phases, ensuring absolute transparency into how the root and chain keys are evolving.

### Source Directory Structure

The core logic lives in **`app/src/main/java/com/example/chatapp/`**:

* **`activities/`**: UI screens (Login, Signup, Chat).
* **`adapters/`**: Logic for rendering chat bubbles and user lists.
* **`crypto/`**: Contains all the cryptographic implementations, including Key Pair generation, the X3DH agreement logic, and the Double Ratchet state management.
* **`listeners/`**: Interfaces listening for UI interactions and real-time Socket events.
* **`models/`**: Data structures representing Users, Keys, and Encrypted Message payloads.
* **`network/`**: Manages HTTP API calls (including key uploads/fetches) and the Socket.IO connection.
* **`utilities/`**: Shared constants, preferences, and secure local storage helpers.

---

## 🔌 API Endpoints (HTTP)

### Authentication & Users

* **`GET /`** : Health check to verify the server is active.
* **`POST /signup`** : Creates a new user account (passwords are hashed).
* **`POST /login`** : Authenticates the user and clears stale ghost sessions.
* **`GET /users?userId=<id>`** : Retrieves all registered users except the current user.
* **`GET /messages?sender=<phone>&receiver=<phone>`** : Fetches the chat history (Returns E2EE ciphertext payloads).

### 🔐 X3DH Key Server (NEW)

* **`POST /keys/upload`** : Uploads a user's generated `identityPublic` and `signedPreKeyPublic` to the server database.
* **`GET /keys?phone=<phone>`** : Fetches the public keys of a specific user to initiate an X3DH key agreement.

---

## ⚡ Socket.IO Events (Blind Relay)

* **`register`** : Connects the client to a private room based on their phone number and flushes pending offline encrypted messages exactly as they were stored.
* **`send_message`** : Acts as a blind relay. It saves the entire dictionary payload (including ciphertext, headers, and ratchet public keys) to MongoDB without modifying it, and routes it to the receiver in real time.
* **`message_read`** : Updates message statuses to read and triggers read-receipt updates (blue ticks).

```

```

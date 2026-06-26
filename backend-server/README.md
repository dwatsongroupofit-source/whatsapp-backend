# WhatsChat Production Secure Cloud Engine

This is the production-ready Node.js full-stack engine for **WhatsChat**. It combines:
1. **Express REST Server**: Manages secure cryptographic sign-ups and user directories.
2. **MongoDB Database integration**: Persists user registration metadata, chats metadata, and encrypted message queues. Highly compatible with **MongoDB Compass**.
3. **Socket.io WebSocket Server**: Provides real-time event broadcasting, allowing E2EE-encrypted text messages, images, and audio notes to be delivered instantly to online recipients.

---

## 🚀 Setup & Local Execution

Follow these simple steps to run this server on your machine alongside **MongoDB Compass**:

### Prerequisite 1: Install Node.js
Ensure you have Node.js (version 18 or above) installed. You can download it from [nodejs.org](https://nodejs.org/).

### Prerequisite 2: Start MongoDB Local Service
1. Install and launch **MongoDB Community Server** and **MongoDB Compass**.
2. Connect MongoDB Compass to your local database using the default connection string:
   ```
   mongodb://localhost:27017/
   ```
3. A database named `whatschat` will be created automatically once you start registering users in the app.

### Installation Instructions
1. Open a terminal or command prompt inside the `backend-server` directory:
   ```bash
   cd backend-server
   ```
2. Install the necessary packages:
   ```bash
   npm install
   ```
3. Start the server in development mode:
   ```bash
   npm run dev
   ```
   *Alternatively, start it in production mode:*
   ```bash
   npm start
   ```

The console will output:
```
=============================================================
🚀 WhatsChat Production Secure Engine is running on port: 5000
🌐 Deployable directly to Heroku, Render, AWS, or local Node
📂 Connected to database URI: mongodb://127.0.0.1:27017/whatschat
=============================================================
```

---

## ⚡ Hosting and Deploying to Heroku

To deploy this backend server to the cloud, you can use Heroku by following these steps:

### Step 1: Install the Heroku CLI
Download the Heroku CLI for your operating system and log in:
```bash
heroku login
```

### Step 2: Initialize Git Repository and Create App
Run the following commands inside the `backend-server` directory:
```bash
git init
git add .
git commit -m "Initial backend release"
heroku create your-unique-whatschat-app-name
```

### Step 3: Configure Database via MongoDB Atlas
1. Create a free-tier database on [MongoDB Atlas](https://www.mongodb.com/cloud/atlas).
2. Grab the connection URL (e.g. `mongodb+srv://<username>:<password>@cluster0.abcde.mongodb.net/whatschat?retryWrites=true&w=majority`).
3. Set the environment variable on Heroku using the config command:
   ```bash
   heroku config:set MONGODB_URI="your_mongodb_atlas_connection_string"
   ```

### Step 4: Deploy your Code
Push your project files to Heroku:
```bash
git push heroku master
```

Once deployment completes, Heroku will provide your production backend URL, for example:
`https://your-unique-whatschat-app-name.herokuapp.com`

**Simply copy-paste this URL into the Cloud Sync card in the app!**

---

## 🔒 Security Architecture (End-to-End Encryption)

WhatsChat is designed with absolute user privacy in mind. Here is how E2EE operates:
1. **Device Generation**: When a user registers, their device generates a custom RSA Public/Private key pair and stores it locally inside their secure Android Vault.
2. **Directory Publication**: Only the user's **Public Key** is uploaded to the Node.js server database. The Private Key never leaves the Android device.
3. **Key Exchange & Encrypted Transmission**: When you send a message, the Android app fetches the recipient's public key from the backend REST API, encrypts the message content on-device, and sends ONLY the encrypted ciphertext and IV over Socket.io.
4. **On-Device Decryption**: The backend server receives only the ciphertext (which looks like unreadable, randomized text). The server relays it to the recipient, who decrypts it locally using their private key.

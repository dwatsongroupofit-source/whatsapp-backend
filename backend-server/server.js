const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const { WebSocketServer } = require('ws');
const WebSocket = require('ws');
const mongoose = require('mongoose');
const cors = require('cors');
require('dotenv').config();

const app = express();
const server = http.createServer(app);

// Configure Socket.io with permissive CORS for dynamic on-device connections
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST', 'OPTIONS'],
    allowedHeaders: ['Content-Type'],
    credentials: true
  }
});

// Configure standard raw WebSocket Server for mobile clients
const wss = new WebSocketServer({ noServer: true });

// Track connected raw WebSocket clients: Map of username -> WebSocket
const rawClients = new Map();

// Upgrade HTTP connections to WebSocket if they request /ws
server.on('upgrade', (request, socket, head) => {
  const { pathname } = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  if (pathname === '/ws') {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request);
    });
  }
});

// Middlewares
app.use(cors());
app.use(express.json());

// -------------------------------------------------------------
// MongoDB Compass / Heroku Database Configurations
// -------------------------------------------------------------
const MONGODB_URI = process.env.MONGODB_URI || 'mongodb://127.0.0.1:27017/whatschat';

console.log('Connecting to MongoDB database...');
mongoose.connect(MONGODB_URI)
  .then(() => console.log('Successfully connected to MongoDB Database!'))
  .catch((err) => {
    console.error('MongoDB database connection error:', err.message);
    console.log('Ensure MongoDB Compass or local MongoDB service is running, or check MONGODB_URI.');
  });

// -------------------------------------------------------------
// Mongoose Models & Schemas
// -------------------------------------------------------------
const userSchema = new mongoose.Schema({
  username: { type: String, required: true, unique: true, lowercase: true, trim: true },
  email: { type: String, required: true, trim: true },
  publicKey: { type: String, required: true },
  avatarUrl: { type: String, default: 'img_profile_avatar' },
  isOnline: { type: Boolean, default: false },
  socketId: { type: String, default: null },
  createdAt: { type: Date, default: Date.now }
});

const messageSchema = new mongoose.Schema({
  messageId: { type: String, required: true, unique: true },
  chatId: { type: String, required: true, index: true },
  senderId: { type: String, required: true },
  senderName: { type: String, required: true },
  ciphertext: { type: String, required: true },
  iv: { type: String, required: true },
  isEncrypted: { type: Boolean, default: true },
  mediaUrl: { type: String, default: null },
  mediaType: { type: String, default: null },
  timestamp: { type: Number, required: true }
});

const chatSchema = new mongoose.Schema({
  chatId: { type: String, required: true, unique: true },
  name: { type: String, required: true },
  isGroup: { type: Boolean, default: false },
  members: [{ type: String }], // Array of usernames
  lastMessageText: { type: String, default: '' },
  lastMessageTimestamp: { type: Number, default: Date.now }
});

const User = mongoose.model('User', userSchema);
const Message = mongoose.model('Message', messageSchema);
const Chat = mongoose.model('Chat', chatSchema);

// -------------------------------------------------------------
// HTTP REST Endpoints
// -------------------------------------------------------------

// Root endpoint with professional welcome message
app.get('/', (req, res) => {
  res.json({
    status: 'ONLINE',
    service: 'WhatsChat Secure Messaging Cloud Engine',
    version: '1.0.0',
    mongodb: mongoose.connection.readyState === 1 ? 'CONNECTED' : 'DISCONNECTED',
    socketsActive: io.sockets.sockets.size
  });
});

// Sign-Up / Register a new cryptographic identity
app.post('/api/users/signup', async (req, res) => {
  const { username, email, publicKey } = req.body;
  
  if (!username || !email || !publicKey) {
    return res.status(400).json({ error: 'Missing required parameters: username, email, or publicKey' });
  }

  try {
    const existingUser = await User.findOne({ username: username.toLowerCase() });
    if (existingUser) {
      return res.status(400).json({ error: 'Username already taken on our cloud network' });
    }

    const newUser = new User({
      username,
      email,
      publicKey
    });

    await newUser.save();
    console.log(`[AUTH] New secure vault identity registered: @${username}`);
    res.status(201).json({ status: 'success', user: newUser });
  } catch (err) {
    res.status(500).json({ error: 'Database enrollment error: ' + err.message });
  }
});

// Search active contacts or users on the cloud server
app.get('/api/users/search', async (req, res) => {
  const { query } = req.query;
  try {
    const users = await User.find({
      username: { $regex: query || '', $options: 'i' }
    }).limit(20);
    res.json(users);
  } catch (err) {
    res.status(500).json({ error: 'Query execution failed: ' + err.message });
  }
});

// Fetch key profile parameters for a specific user username (useful for E2EE key exchange)
app.get('/api/users/:username', async (req, res) => {
  try {
    const user = await User.findOne({ username: req.params.username.toLowerCase() });
    if (!user) {
      return res.status(404).json({ error: 'User not found in network directory' });
    }
    res.json(user);
  } catch (err) {
    res.status(500).json({ error: 'Failed to retrieve profile: ' + err.message });
  }
});

// Create/Register a Chat room channel
app.post('/api/chats/create', async (req, res) => {
  const { chatId, name, isGroup, members } = req.body;
  if (!chatId || !name) {
    return res.status(400).json({ error: 'Missing chatId or name' });
  }
  try {
    let chat = await Chat.findOne({ chatId });
    if (!chat) {
      chat = new Chat({ chatId, name, isGroup, members: members || [] });
      await chat.save();
    }
    res.json(chat);
  } catch (err) {
    res.status(500).json({ error: 'Failed to create chat: ' + err.message });
  }
});

// Get chat directory
app.get('/api/chats', async (req, res) => {
  try {
    const chats = await Chat.find().sort({ lastMessageTimestamp: -1 });
    res.json(chats);
  } catch (err) {
    res.status(500).json({ error: 'Failed to retrieve chats: ' + err.message });
  }
});

// Fetch secure messages history for a chat channel
app.get('/api/messages/:chatId', async (req, res) => {
  try {
    const messages = await Message.find({ chatId: req.params.chatId }).sort({ timestamp: 1 });
    res.json(messages);
  } catch (err) {
    res.status(500).json({ error: 'Message retrieval failed: ' + err.message });
  }
});

// -------------------------------------------------------------
// Real-Time Socket.io Connection Logic
// -------------------------------------------------------------
io.on('connection', (socket) => {
  console.log(`[SOCKET] Incoming link opened: ID ${socket.id}`);

  // Register device user with socket.io
  socket.on('register_user', async (data) => {
    const { username } = data;
    if (!username) return;

    try {
      const user = await User.findOneAndUpdate(
        { username: username.toLowerCase() },
        { isOnline: true, socketId: socket.id },
        { new: true }
      );

      if (user) {
        socket.username = username.toLowerCase();
        console.log(`[SOCKET] Connected/Authorized: @${username} is now online`);
        
        // Broadcast presence
        socket.broadcast.emit('user_presence_change', {
          username: username.toLowerCase(),
          isOnline: true
        });

        // Send active online users list back to the registrant
        const onlineUsers = await User.find({ isOnline: true }).select('username email avatarUrl');
        socket.emit('online_directory', onlineUsers);
      } else {
        socket.emit('auth_error', { message: 'Username does not exist in central registry. Sign up first.' });
      }
    } catch (err) {
      console.error('Socket login error:', err.message);
    }
  });

  // Join designated Chat Room Channels
  socket.on('join_chat', (data) => {
    const { chatId } = data;
    if (!chatId) return;
    socket.join(chatId);
    console.log(`[SOCKET] User @${socket.username || 'unknown'} joined chat channel: ${chatId}`);
  });

  // Route secure E2EE payload between devices
  socket.on('secure_send_message', async (messageData) => {
    const { messageId, chatId, senderId, senderName, ciphertext, iv, mediaUrl, mediaType, timestamp } = messageData;
    
    if (!chatId || !senderId || !ciphertext || !iv) {
      console.log('[SOCKET] Blocked invalid message packet structure');
      return;
    }

    try {
      // 1. Persist the encrypted message payload inside MongoDB database
      const newMessage = new Message({
        messageId: messageId || `msg_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
        chatId,
        senderId,
        senderName,
        ciphertext,
        iv,
        mediaUrl,
        mediaType,
        timestamp: timestamp || Date.now()
      });
      await newMessage.save();

      // 2. Update Chat's preview context
      const previewText = mediaType === 'image' ? '📷 Photo' : (mediaType === 'audio' ? '🎵 Voice note' : 'Encrypted Message');
      await Chat.findOneAndUpdate(
        { chatId },
        { lastMessageText: previewText, lastMessageTimestamp: timestamp || Date.now() },
        { upsert: true }
      );

      // 3. Forward message to all clients in the designated chat room (except the sender)
      socket.to(chatId).emit('secure_receive_message', messageData);
      
      console.log(`[SOCKET] Encrypted packet routed correctly from @${senderId} inside chat ${chatId}`);
    } catch (err) {
      console.error('Message routing failure:', err.message);
    }
  });

  // Disconnect handler
  socket.on('disconnect', async () => {
    console.log(`[SOCKET] Device disconnected: ID ${socket.id}`);
    if (socket.username) {
      try {
        await User.findOneAndUpdate(
          { username: socket.username },
          { isOnline: false, socketId: null }
        );
        
        // Broadcast presence
        socket.broadcast.emit('user_presence_change', {
          username: socket.username,
          isOnline: false
        });
        console.log(`[SOCKET] @${socket.username} is offline`);
      } catch (err) {
        console.error('Offline state persistence error:', err.message);
      }
    }
  });
});

// -------------------------------------------------------------
// Real-Time Native WebSockets Connection Logic (for Android app)
// -------------------------------------------------------------
wss.on('connection', (ws, request) => {
  // Extract username query param from request URL: ws://domain.com/ws?username=alice
  const urlParts = request.url.split('?');
  const urlParams = new URLSearchParams(urlParts.length > 1 ? urlParts[1] : '');
  const username = (urlParams.get('username') || '').toLowerCase().trim();

  if (!username) {
    console.log('[WS] Rejected connection request: missing username parameter');
    ws.close(4000, 'Missing username parameter');
    return;
  }

  ws.username = username;
  rawClients.set(username, ws);
  console.log(`[WS] Raw WebSocket client connected & mapped: @${username}`);

  // Mark user online in MongoDB
  User.findOneAndUpdate(
    { username: username },
    { isOnline: true },
    { new: true }
  ).then((user) => {
    if (user) {
      // 1. Broadcast presence to all Socket.io clients
      io.emit('user_presence_change', {
        username: username,
        isOnline: true
      });

      // 2. Broadcast presence to all other raw WebSocket clients
      broadcastRawPresence(username, true);
    }
  }).catch((err) => {
    console.error('[WS] Presence status change database failure:', err.message);
  });

  // Handle incoming data packages over raw WebSockets
  ws.on('message', async (messageBuffer) => {
    try {
      const messageStr = messageBuffer.toString();
      const packet = JSON.parse(messageStr);

      if (packet.type === 'send_message') {
        const messageData = packet.data;
        const { messageId, chatId, senderId, senderName, ciphertext, iv, mediaUrl, mediaType, timestamp } = messageData;

        if (!chatId || !senderId || !ciphertext || !iv) {
          console.log('[WS] Rejected invalid packet: incomplete E2EE structure');
          return;
        }

        // 1. Save E2EE-encrypted payload inside MongoDB database
        const newMessage = new Message({
          messageId: messageId || `msg_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
          chatId,
          senderId,
          senderName,
          ciphertext,
          iv,
          mediaUrl,
          mediaType,
          timestamp: timestamp || Date.now()
        });
        await newMessage.save();

        // 2. Update parent Chat summary context
        const previewText = mediaType === 'image' ? '📷 Photo' : (mediaType === 'audio' ? '🎵 Voice note' : 'Encrypted Message');
        await Chat.findOneAndUpdate(
          { chatId },
          { lastMessageText: previewText, lastMessageTimestamp: timestamp || Date.now() },
          { upsert: true }
        );

        // 3. Broadcast to all active Socket.io listeners in the room
        io.to(chatId).emit('secure_receive_message', messageData);

        // 4. Broadcast to all other online raw WebSocket client devices
        const forwardPacket = JSON.stringify({
          type: 'receive_message',
          data: messageData
        });

        rawClients.forEach((clientWs, clientUsername) => {
          if (clientUsername !== username && clientWs.readyState === WebSocket.OPEN) {
            clientWs.send(forwardPacket);
          }
        });

        console.log(`[WS] Routed secure E2EE packet correctly from @${senderId} over Raw WS channel`);
      }
    } catch (err) {
      console.error('[WS] Failed to parse raw message packet:', err.message);
    }
  });

  // Handle connection close event
  ws.on('close', async () => {
    console.log(`[WS] Raw WebSocket link closed: @${username}`);
    rawClients.delete(username);

    try {
      await User.findOneAndUpdate(
        { username: username },
        { isOnline: false }
      );

      // 1. Broadcast offline state to Socket.io clients
      io.emit('user_presence_change', {
        username: username,
        isOnline: false
      });

      // 2. Broadcast offline state to other raw WebSocket devices
      broadcastRawPresence(username, false);
    } catch (err) {
      console.error('[WS] Database offline cleanup failed:', err.message);
    }
  });
});

// Helper to broadcast presence updates over raw WebSockets
function broadcastRawPresence(username, isOnline) {
  const presencePacket = JSON.stringify({
    type: 'presence_change',
    data: { username, isOnline }
  });

  rawClients.forEach((clientWs, clientUsername) => {
    if (clientUsername !== username && clientWs.readyState === WebSocket.OPEN) {
      clientWs.send(presencePacket);
    }
  });
}

// -------------------------------------------------------------
// Bind & Initialize Service
// -------------------------------------------------------------
const PORT = process.env.PORT || 5000;
server.listen(PORT, () => {
  console.log(`=============================================================`);
  console.log(`🚀 WhatsChat Production Secure Engine is running on port: ${PORT}`);
  console.log(`🌐 Deployable directly to Heroku, Render, AWS, or local Node`);
  console.log(`📂 Connected to database URI: ${MONGODB_URI}`);
  console.log(`=============================================================`);
});

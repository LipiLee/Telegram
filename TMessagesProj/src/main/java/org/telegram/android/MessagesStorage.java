/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package org.telegram.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.SparseArray;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.BuffersStorage;
import org.telegram.messenger.ByteBufferDesc;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.TLClassStore;
import org.telegram.messenger.TLObject;
import org.telegram.messenger.TLRPC;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ApplicationLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class MessagesStorage {
    public DispatchQueue storageQueue = new DispatchQueue("storageQueue");
    private SQLiteDatabase database;
    private File cacheFile;
    private BuffersStorage buffersStorage = new BuffersStorage(false);
    public static int lastDateValue = 0;
    public static int lastPtsValue = 0;
    public static int lastQtsValue = 0;
    public static int lastSeqValue = 0;
    public static int lastSecretVersion = 0;
    public static byte[] secretPBytes = null;
    public static int secretG = 0;

    private int lastSavedSeq = 0;
    private int lastSavedPts = 0;
    private int lastSavedDate = 0;
    private int lastSavedQts = 0;

    private static volatile MessagesStorage Instance = null;
    public static MessagesStorage getInstance() {
        MessagesStorage localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesStorage.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MessagesStorage();
                }
            }
        }
        return localInstance;
    }

    public MessagesStorage() {
        storageQueue.setPriority(Thread.MAX_PRIORITY);
        openDatabase();
    }

    public void openDatabase() {
        cacheFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache4.db");

        boolean createTable = false;
        //cacheFile.delete();
        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {
            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = 1").stepThis().dispose();
            if (createTable) {
                database.executeFast("CREATE TABLE users(uid INTEGER PRIMARY KEY, name TEXT, status INTEGER, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE messages(mid INTEGER PRIMARY KEY, uid INTEGER, read_state INTEGER, send_state INTEGER, date INTEGER, data BLOB, out INTEGER, ttl INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE chats(uid INTEGER PRIMARY KEY, name TEXT, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE enc_chats(uid INTEGER PRIMARY KEY, user INTEGER, name TEXT, data BLOB, g BLOB, authkey BLOB, ttl INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE dialogs(did INTEGER PRIMARY KEY, date INTEGER, unread_count INTEGER, last_mid INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE chat_settings(uid INTEGER PRIMARY KEY, participants BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE contacts(uid INTEGER PRIMARY KEY, mutual INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE pending_read(uid INTEGER PRIMARY KEY, max_id INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE media(mid INTEGER PRIMARY KEY, uid INTEGER, date INTEGER, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE media_counts(uid INTEGER PRIMARY KEY, count INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE wallpapers(uid INTEGER PRIMARY KEY, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE randoms(random_id INTEGER PRIMARY KEY, mid INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE enc_tasks(date INTEGER, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
                database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();
                database.executeFast("CREATE TABLE user_photos(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();
                database.executeFast("CREATE TABLE blocked_users(uid INTEGER PRIMARY KEY)").stepThis().dispose();
                database.executeFast("CREATE TABLE download_queue(uid INTEGER, type INTEGER, date INTEGER, data BLOB, PRIMARY KEY (uid, type));").stepThis().dispose();
                database.executeFast("CREATE TABLE dialog_settings(did INTEGER PRIMARY KEY, flags INTEGER);").stepThis().dispose();
                //database.executeFast("CREATE TABLE attach_data(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();

                database.executeFast("CREATE TABLE user_contacts_v6(uid INTEGER PRIMARY KEY, fname TEXT, sname TEXT)").stepThis().dispose();
                database.executeFast("CREATE TABLE user_phones_v6(uid INTEGER, phone TEXT, sphone TEXT, deleted INTEGER, PRIMARY KEY (uid, phone))").stepThis().dispose();

                database.executeFast("CREATE TABLE sent_files_v2(uid TEXT, type INTEGER, data BLOB, PRIMARY KEY (uid, type))").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS type_date_idx_download_queue ON download_queue(type, date);").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_randoms ON randoms(mid);").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS sphone_deleted_idx_user_phones ON user_phones_v6(sphone, deleted);").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_dialogs ON dialogs(date);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_enc_tasks ON enc_tasks(date);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_idx_dialogs ON dialogs(last_mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_idx_dialogs ON dialogs(unread_count);").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_idx_media ON media(uid, mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_media ON media(mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_media ON media(uid, date, mid);").stepThis().dispose();

                database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_idx_messages ON messages(uid, mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages ON messages(uid, date, mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages ON messages(mid, out);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages ON messages(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages ON messages(mid, send_state, date) WHERE mid < 0 AND send_state = 1;").stepThis().dispose();
                database.executeFast("PRAGMA user_version = 4").stepThis().dispose();
            } else {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT seq, pts, date, qts, lsv, sg, pbytes FROM params WHERE id = 1");
                    if (cursor.next()) {
                        lastSeqValue = cursor.intValue(0);
                        lastPtsValue = cursor.intValue(1);
                        lastDateValue = cursor.intValue(2);
                        lastQtsValue = cursor.intValue(3);
                        lastSecretVersion = cursor.intValue(4);
                        secretG = cursor.intValue(5);
                        if (cursor.isNull(6)) {
                            secretPBytes = null;
                        } else {
                            secretPBytes = cursor.byteArrayValue(6);
                            if (secretPBytes != null && secretPBytes.length == 1) {
                                secretPBytes = null;
                            }
                        }
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    try {
                        database.executeFast("CREATE TABLE IF NOT EXISTS params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
                        database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();
                    } catch (Exception e2) {
                        FileLog.e("tmessages", e2);
                    }
                }

                int version = database.executeInt("PRAGMA user_version");
                if (version < 4) {
                    updateDbToVersion4();
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        loadUnreadMessages();
    }

    public void updateDbToVersion4() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("CREATE TABLE IF NOT EXISTS user_photos(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();

                    database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_media ON media(mid);").stepThis().dispose();
                    database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_media ON media(uid, date, mid);").stepThis().dispose();

                    database.executeFast("DROP INDEX IF EXISTS read_state_out_idx_messages;").stepThis().dispose();
                    database.executeFast("DROP INDEX IF EXISTS ttl_idx_messages;").stepThis().dispose();
                    database.executeFast("DROP INDEX IF EXISTS date_idx_messages;").stepThis().dispose();

                    database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages ON messages(mid, out);").stepThis().dispose();
                    database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages ON messages(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
                    database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages ON messages(uid, date, mid);").stepThis().dispose();

                    database.executeFast("CREATE TABLE IF NOT EXISTS user_contacts_v6(uid INTEGER PRIMARY KEY, fname TEXT, sname TEXT)").stepThis().dispose();
                    database.executeFast("CREATE TABLE IF NOT EXISTS user_phones_v6(uid INTEGER, phone TEXT, sphone TEXT, deleted INTEGER, PRIMARY KEY (uid, phone))").stepThis().dispose();
                    database.executeFast("CREATE INDEX IF NOT EXISTS sphone_deleted_idx_user_phones ON user_phones_v6(sphone, deleted);").stepThis().dispose();

                    database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_randoms ON randoms(mid);").stepThis().dispose();

                    database.executeFast("CREATE TABLE IF NOT EXISTS sent_files_v2(uid TEXT, type INTEGER, data BLOB, PRIMARY KEY (uid, type))").stepThis().dispose();

                    database.executeFast("CREATE TABLE IF NOT EXISTS blocked_users(uid INTEGER PRIMARY KEY)").stepThis().dispose();

                    database.executeFast("CREATE TABLE IF NOT EXISTS download_queue(uid INTEGER, type INTEGER, date INTEGER, data BLOB, PRIMARY KEY (uid, type));").stepThis().dispose();
                    database.executeFast("CREATE INDEX IF NOT EXISTS type_date_idx_download_queue ON download_queue(type, date);").stepThis().dispose();

                    database.executeFast("CREATE TABLE IF NOT EXISTS dialog_settings(did INTEGER PRIMARY KEY, flags INTEGER);").stepThis().dispose();

                    database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages ON messages(mid, send_state, date) WHERE mid < 0 AND send_state = 1;").stepThis().dispose();

                    database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_idx_dialogs ON dialogs(unread_count);").stepThis().dispose();

                    database.executeFast("UPDATE messages SET send_state = 2 WHERE mid < 0 AND send_state = 1").stepThis().dispose();

                    database.executeFast("PRAGMA user_version = 4").stepThis().dispose();
                    storageQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            ArrayList<Integer> ids = new ArrayList<Integer>();
                            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                            Map<String, ?> values = preferences.getAll();
                            for (Map.Entry<String, ?> entry : values.entrySet()) {
                                String key = entry.getKey();
                                if (key.startsWith("notify2_")) {
                                    Integer value = (Integer)entry.getValue();
                                    if (value == 2) {
                                        key = key.replace("notify2_", "");
                                        try {
                                            ids.add(Integer.parseInt(key));
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                            try {
                                database.beginTransaction();
                                SQLitePreparedStatement state = database.executeFast("REPLACE INTO dialog_settings VALUES(?, ?)");
                                for (Integer id : ids) {
                                    state.requery();
                                    state.bindLong(1, id);
                                    state.bindInteger(2, 1);
                                    state.step();
                                }
                                state.dispose();
                                database.commitTransaction();
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void cleanUp(final boolean isLogin) {
        storageQueue.cleanupQueue();
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                lastDateValue = 0;
                lastSeqValue = 0;
                lastPtsValue = 0;
                lastQtsValue = 0;
                lastSecretVersion = 0;

                lastSavedSeq = 0;
                lastSavedPts = 0;
                lastSavedDate = 0;
                lastSavedQts = 0;

                secretPBytes = null;
                secretG = 0;
                if (database != null) {
                    database.close();
                    database = null;
                }
                if (cacheFile != null) {
                    cacheFile.delete();
                    cacheFile = null;
                }
                openDatabase();
                if (isLogin) {
                    Utilities.stageQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            MessagesController.getInstance().getDifference();
                        }
                    });
                }
            }
        });
    }

    public void saveSecretParams(final int lsv, final int sg, final byte[] pbytes) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("UPDATE params SET lsv = ?, sg = ?, pbytes = ? WHERE id = 1");
                    state.bindInteger(1, lsv);
                    state.bindInteger(2, sg);
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(pbytes != null ? pbytes.length : 1);
                    if (pbytes != null) {
                        data.writeRaw(pbytes);
                    }
                    state.bindByteBuffer(3, data.buffer);
                    state.step();
                    state.dispose();
                    buffersStorage.reuseFreeBuffer(data);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void saveDiffParams(final int seq, final int pts, final int date, final int qts) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lastSavedSeq == seq && lastSavedPts == pts && lastSavedDate == date && lastQtsValue == qts) {
                        return;
                    }
                    SQLitePreparedStatement state = database.executeFast("UPDATE params SET seq = ?, pts = ?, date = ?, qts = ? WHERE id = 1");
                    state.bindInteger(1, seq);
                    state.bindInteger(2, pts);
                    state.bindInteger(3, date);
                    state.bindInteger(4, qts);
                    state.step();
                    state.dispose();
                    lastSavedSeq = seq;
                    lastSavedPts = pts;
                    lastSavedDate = date;
                    lastSavedQts = qts;
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void setDialogFlags(final long did, final int flags) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast(String.format(Locale.US, "REPLACE INTO dialog_settings VALUES(%d, %d)", did, flags)).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadUnreadMessages() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    final HashMap<Long, Integer> pushDialogs = new HashMap<Long, Integer>();
                    SQLiteCursor cursor = database.queryFinalized("SELECT d.did, d.unread_count, s.flags FROM dialogs as d LEFT JOIN dialog_settings as s ON d.did = s.did WHERE d.unread_count != 0");
                    String ids = "";
                    while (cursor.next()) {
                        if (cursor.isNull(2) || cursor.intValue(2) != 1) {
                            long did = cursor.longValue(0);
                            int count = cursor.intValue(1);
                            pushDialogs.put(did, count);
                            if (ids.length() != 0) {
                                ids += ",";
                            }
                            ids += did;
                        }
                    }
                    cursor.dispose();

                    final ArrayList<TLRPC.Message> messages = new ArrayList<TLRPC.Message>();
                    final ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
                    final ArrayList<TLRPC.Chat> chats = new ArrayList<TLRPC.Chat>();
                    final ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<TLRPC.EncryptedChat>();
                    if (ids.length() > 0) {
                        ArrayList<Integer> userIds = new ArrayList<Integer>();
                        ArrayList<Integer> chatIds = new ArrayList<Integer>();
                        ArrayList<Integer> encryptedChatIds = new ArrayList<Integer>();

                        cursor = database.queryFinalized("SELECT read_state, data, send_state, mid, date, uid FROM messages WHERE uid IN (" + ids + ") AND out = 0 AND read_state = 0 ORDER BY date DESC LIMIT 50");
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(1));
                            if (data != null && cursor.byteBufferValue(1, data.buffer) != 0) {
                                TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                int read_state = cursor.intValue(0);
                                message.unread = (cursor.intValue(0) != 1);
                                message.id = cursor.intValue(3);
                                message.date = cursor.intValue(4);
                                message.dialog_id = cursor.longValue(5);
                                messages.add(message);

                                int lower_id = (int)message.dialog_id;
                                int high_id = (int)(message.dialog_id >> 32);

                                if (lower_id != 0) {
                                    if (lower_id < 0) {
                                        if (!chatIds.contains(-lower_id)) {
                                            chatIds.add(-lower_id);
                                        }
                                    } else {
                                        if (!userIds.contains(lower_id)) {
                                            userIds.add(lower_id);
                                        }
                                    }
                                } else {
                                    if (!encryptedChatIds.contains(high_id)) {
                                        encryptedChatIds.add(high_id);
                                    }
                                }

                                if (!userIds.contains(message.from_id)) {
                                    userIds.add(message.from_id);
                                }
                                if (message.action != null && message.action.user_id != 0 && !userIds.contains(message.action.user_id)) {
                                    userIds.add(message.action.user_id);
                                }
                                if (message.media != null && message.media.user_id != 0 && !userIds.contains(message.media.user_id)) {
                                    userIds.add(message.media.user_id);
                                }
                                if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0 && !userIds.contains(message.media.audio.user_id)) {
                                    userIds.add(message.media.audio.user_id);
                                }
                                if (message.fwd_from_id != 0 && !userIds.contains(message.fwd_from_id)) {
                                    userIds.add(message.fwd_from_id);
                                }
                                message.send_state = cursor.intValue(2);
                                if (!message.unread && lower_id != 0 || message.id > 0) {
                                    message.send_state = 0;
                                }
                                if (lower_id == 0 && !cursor.isNull(5)) {
                                    message.random_id = cursor.longValue(5);
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();

                        String stringToLoad = "";
                        if (!encryptedChatIds.isEmpty()) {
                            for (int uid : encryptedChatIds) {
                                if (stringToLoad.length() != 0) {
                                    stringToLoad += ",";
                                }
                                stringToLoad += uid;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid IN(%s)", stringToLoad));
                            while (cursor.next()) {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    encryptedChats.add(chat);
                                    chat.user_id = cursor.intValue(1);
                                    if (!userIds.contains(chat.user_id)) {
                                        userIds.add(chat.user_id);
                                    }
                                    chat.a_or_b = cursor.byteArrayValue(2);
                                    chat.auth_key = cursor.byteArrayValue(3);
                                    chat.ttl = cursor.intValue(4);
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            }
                            cursor.dispose();
                        }

                        if (!userIds.isEmpty()) {
                            stringToLoad = "";
                            for (Integer uid : userIds) {
                                if (stringToLoad.length() != 0) {
                                    stringToLoad += ",";
                                }
                                stringToLoad += uid;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", stringToLoad));
                            while (cursor.next()) {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.User user = (TLRPC.User) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    if (user.status != null) {
                                        user.status.expires = cursor.intValue(1);
                                    }
                                    users.add(user);
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            }
                            cursor.dispose();
                        }

                        if (!chatIds.isEmpty()) {
                            stringToLoad = "";
                            for (Integer cid : chatIds) {
                                if (stringToLoad.length() != 0) {
                                    stringToLoad += ",";
                                }
                                stringToLoad += cid;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid IN(%s)", stringToLoad));
                            while (cursor.next()) {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.Chat chat = (TLRPC.Chat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    chats.add(chat);
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            }
                            cursor.dispose();
                        }
                    }
                    Collections.reverse(messages);
                    AndroidUtilities.RunOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationsController.getInstance().processLoadedUnreadMessages(pushDialogs, messages, users, chats, encryptedChats);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putWallpapers(final ArrayList<TLRPC.WallPaper> wallPapers) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int num = 0;
                    database.executeFast("DELETE FROM wallpapers WHERE 1").stepThis().dispose();
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO wallpapers VALUES(?, ?)");
                    for (TLRPC.WallPaper wallPaper : wallPapers) {
                        state.requery();
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(wallPaper.getObjectSize());
                        wallPaper.serializeToStream(data);
                        state.bindInteger(1, num);
                        state.bindByteBuffer(2, data.buffer);
                        state.step();
                        num++;
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getWallpapers() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT data FROM wallpapers WHERE 1");
                    ArrayList<TLRPC.WallPaper> wallPapers = new ArrayList<TLRPC.WallPaper>();
                    while (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            TLRPC.WallPaper wallPaper = (TLRPC.WallPaper) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            wallPapers.add(wallPaper);
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();
                    NotificationCenter.getInstance().postNotificationName(NotificationCenter.wallpapersDidLoaded, wallPapers);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getBlockedUsers() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<Integer> ids = new ArrayList<Integer>();
                    ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT * FROM blocked_users WHERE 1"));
                    String usersToLoad = "";
                    while (cursor.next()) {
                        int user_id = cursor.intValue(0);
                        ids.add(user_id);
                        if (usersToLoad.length() != 0) {
                            usersToLoad += ",";
                        }
                        usersToLoad += user_id;
                    }
                    cursor.dispose();

                    if (usersToLoad.length() != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", usersToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                users.add(user);
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }

                    MessagesController.getInstance().processLoadedBlockedUsers(ids, users, true);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteBlockedUser(final int id) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM blocked_users WHERE uid = " + id).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putBlockedUsers(final ArrayList<Integer> ids, final boolean replace) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (replace) {
                        database.executeFast("DELETE FROM blocked_users WHERE 1").stepThis().dispose();
                    }
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO blocked_users VALUES(?)");
                    for (Integer id : ids) {
                        state.requery();
                        state.bindInteger(1, id);
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteDialog(final long did, final boolean messagesOnly) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!messagesOnly) {
                        database.executeFast("DELETE FROM dialogs WHERE did = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM chat_settings WHERE uid = " + did).stepThis().dispose();
                        int lower_id = (int)did;
                        int high_id = (int)(did >> 32);
                        if (lower_id != 0) {
                            if (high_id == 1) {
                                database.executeFast("DELETE FROM chats WHERE uid = " + lower_id).stepThis().dispose();
                            } else if (lower_id < 0) {
                                database.executeFast("DELETE FROM chats WHERE uid = " + (-lower_id)).stepThis().dispose();
                            }
                        } else {
                            database.executeFast("DELETE FROM enc_chats WHERE uid = " + high_id).stepThis().dispose();
                        }
                    }
                    database.executeFast("UPDATE dialogs SET unread_count = 0 WHERE did = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_counts WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media WHERE uid = " + did).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getUserPhotos(final int uid, final int offset, final int count, final long max_id, final int classGuid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor;

                    if (max_id != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d AND id < %d ORDER BY id DESC LIMIT %d", uid, max_id, count));
                    } else {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d ORDER BY id DESC LIMIT %d,%d", uid, offset, count));
                    }

                    final TLRPC.photos_Photos res = new TLRPC.photos_Photos();

                    while (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            TLRPC.Photo photo = (TLRPC.Photo)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            res.photos.add(photo);
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();

                    Utilities.stageQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            MessagesController.getInstance().processLoadedUserPhotos(res, uid, offset, count, max_id, true, classGuid);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearUserPhotos(final int uid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM user_photos WHERE uid = " + uid).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearUserPhoto(final int uid, final long pid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM user_photos WHERE uid = " + uid + " AND id = " + pid).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putUserPhotos(final int uid, final TLRPC.photos_Photos photos) {
        if (photos == null || photos.photos.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO user_photos VALUES(?, ?, ?)");
                    for (TLRPC.Photo photo : photos.photos) {
                        if (photo instanceof TLRPC.TL_photoEmpty) {
                            continue;
                        }
                        state.requery();
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(photo.getObjectSize());
                        photo.serializeToStream(data);
                        state.bindInteger(1, uid);
                        state.bindLong(2, photo.id);
                        state.bindByteBuffer(3, data.buffer);
                        state.step();
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getNewTask(final Long oldTask) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (oldTask != null) {
                        database.executeFast("DELETE FROM enc_tasks WHERE rowid = " + oldTask).stepThis().dispose();
                    }
                    Long taskId = null;
                    int date = 0;
                    ArrayList<Integer> arr = null;
                    SQLiteCursor cursor = database.queryFinalized("SELECT rowid, date, data FROM enc_tasks ORDER BY date ASC LIMIT 1");
                    if (cursor.next()) {
                        taskId = cursor.longValue(0);
                        date = cursor.intValue(1);

                        int length = 0;
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(2));
                        if ((length = cursor.byteBufferValue(2, data.buffer)) != 0) {
                            arr = new ArrayList<Integer>();
                            for (int a = 0; a < length / 4; a++) {
                                arr.add(data.readInt32());
                            }
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();
                    MessagesController.getInstance().processLoadedDeleteTask(taskId, date, arr);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void createTaskForDate(final int chat_id, final int time, final int readTime, final int isOut) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int minDate = Integer.MAX_VALUE;
                    SparseArray<ArrayList<Integer>> messages = new SparseArray<ArrayList<Integer>>();
                    String mids = "";
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid, ttl, read_state FROM messages WHERE uid = %d AND out = %d AND ttl > 0 AND date <= %d AND send_state = 0", ((long)chat_id) << 32, isOut, time));
                    while (cursor.next()) {
                        int mid = cursor.intValue(0);
                        int ttl = cursor.intValue(1);
                        int read_state = cursor.intValue(2);
                        int date = readTime + ttl;
                        minDate = Math.min(minDate, date);
                        ArrayList<Integer> arr = messages.get(date);
                        if (arr == null) {
                            arr = new ArrayList<Integer>();
                            messages.put(date, arr);
                        }
                        if (mids.length() != 0) {
                            mids += ",";
                        }
                        mids += "" + mid;
                        arr.add(mid);
                    }
                    cursor.dispose();
                    if (messages.size() != 0) {
                        database.beginTransaction();
                        SQLitePreparedStatement state = database.executeFast("INSERT INTO enc_tasks VALUES(?, ?)");
                        for (int a = 0; a < messages.size(); a++) {
                            int key = messages.keyAt(a);
                            ArrayList<Integer> arr = messages.get(key);
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(404);
                            int count = 0;
                            for (int b = 0; b < arr.size(); b++) {
                                int mid = arr.get(b);
                                data.writeInt32(mid);
                                count++;
                                if (b == arr.size() - 1 || b != 0 && b % 100 == 0) {
                                    state.requery();
                                    data.limit(count * 4);
                                    state.bindInteger(1, key);
                                    state.bindByteBuffer(2, data.buffer);
                                    state.step();
                                    count = 0;
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                        database.commitTransaction();
                        database.executeFast(String.format(Locale.US, "UPDATE messages SET ttl = 0 WHERE mid IN(%s)", mids)).stepThis().dispose();
                        MessagesController.getInstance().didAddedNewTask(minDate);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void updateDialogsWithReadedMessagesInternal(final ArrayList<Integer> messages) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            HashMap<Long, Integer> dialogsToUpdate = new HashMap<Long, Integer>();
            String dialogsToReload = "";
            if (messages != null && !messages.isEmpty()) {
                String ids = "";
                for (int uid : messages) {
                    if (ids.length() != 0) {
                        ids += ",";
                    }
                    ids += uid;
                }
                int totalCount = 0;
                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, read_state, out FROM messages WHERE mid IN(%s)", ids));
                while (cursor.next()) {
                    int out = cursor.intValue(2);
                    totalCount++;
                    if (out != 0) {
                        continue;
                    }
                    int read_state = cursor.intValue(1);
                    if (read_state != 0) {
                        continue;
                    }
                    long uid = cursor.longValue(0);
                    Integer currentCount = dialogsToUpdate.get(uid);
                    if (currentCount == null) {
                        dialogsToUpdate.put(uid, 1);
                        if (dialogsToReload.length() != 0) {
                            dialogsToReload += ",";
                        }
                        dialogsToReload += uid;
                    } else {
                        dialogsToUpdate.put(uid, currentCount + 1);
                    }
                }
                cursor.dispose();

                if (totalCount != messages.size()) {
                    FileLog.e("tmessages", "messages read mismatch!");
                }

                cursor = database.queryFinalized(String.format(Locale.US, "SELECT did, unread_count FROM dialogs WHERE did IN(%s)", dialogsToReload));
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    int count = cursor.intValue(1);
                    Integer currentCount = dialogsToUpdate.get(did);
                    if (currentCount != null) {
                        dialogsToUpdate.put(did, Math.max(0, count - currentCount));
                    } else {
                        dialogsToUpdate.remove(did);
                    }
                }
                cursor.dispose();

                database.beginTransaction();
                SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET unread_count = ? WHERE did = ?");
                for (HashMap.Entry<Long, Integer> entry : dialogsToUpdate.entrySet()) {
                    state.requery();
                    state.bindInteger(1, entry.getValue());
                    state.bindLong(2, entry.getKey());
                    state.step();
                }
                state.dispose();
                database.commitTransaction();
            }

            if (!dialogsToUpdate.isEmpty()) {
                MessagesController.getInstance().processDialogsUpdateRead(dialogsToUpdate);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateDialogsWithReadedMessages(final ArrayList<Integer> messages, boolean useQueue) {
        if (messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateDialogsWithReadedMessagesInternal(messages);
                }
            });
        } else {
            updateDialogsWithReadedMessagesInternal(messages);
        }
    }

    public void updateChatInfo(final int chat_id, final TLRPC.ChatParticipants info, final boolean ifExist) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ifExist) {
                        boolean dontExist = true;
                        SQLiteCursor cursor = database.queryFinalized("SELECT uid FROM chat_settings WHERE uid = " + chat_id);
                        if (cursor.next()) {
                            dontExist = false;
                        }
                        cursor.dispose();
                        if (dontExist) {
                            return;
                        }
                    }
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings VALUES(?, ?)");
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(info.getObjectSize());
                    info.serializeToStream(data);
                    state.bindInteger(1, chat_id);
                    state.bindByteBuffer(2, data.buffer);
                    state.step();
                    state.dispose();
                    buffersStorage.reuseFreeBuffer(data);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void updateChatInfo(final int chat_id, final int user_id, final boolean deleted, final int invited_id, final int version) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT participants FROM chat_settings WHERE uid = " + chat_id);
                    TLRPC.ChatParticipants info = null;
                    ArrayList<TLRPC.User> loadedUsers = new ArrayList<TLRPC.User>();
                    if (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            info = (TLRPC.ChatParticipants)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();
                    if (info != null) {
                        if (deleted) {
                            for (int a = 0; a < info.participants.size(); a++) {
                                TLRPC.TL_chatParticipant participant = info.participants.get(a);
                                if (participant.user_id == user_id) {
                                    info.participants.remove(a);
                                    break;
                                }
                            }
                        } else {
                            for (TLRPC.TL_chatParticipant part : info.participants) {
                                if (part.user_id == user_id) {
                                    return;
                                }
                            }
                            TLRPC.TL_chatParticipant participant = new TLRPC.TL_chatParticipant();
                            participant.user_id = user_id;
                            participant.inviter_id = invited_id;
                            participant.date = ConnectionsManager.getInstance().getCurrentTime();
                            info.participants.add(participant);
                        }
                        info.version = version;

                        final TLRPC.ChatParticipants finalInfo = info;
                        AndroidUtilities.RunOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.chatInfoDidLoaded, finalInfo.chat_id, finalInfo);
                            }
                        });

                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings VALUES(?, ?)");
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(info.getObjectSize());
                        info.serializeToStream(data);
                        state.bindInteger(1, chat_id);
                        state.bindByteBuffer(2, data.buffer);
                        state.step();
                        state.dispose();
                        buffersStorage.reuseFreeBuffer(data);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadChatInfo(final int chat_id, final Semaphore semaphore) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT participants FROM chat_settings WHERE uid = " + chat_id);
                    TLRPC.ChatParticipants info = null;
                    ArrayList<TLRPC.User> loadedUsers = new ArrayList<TLRPC.User>();
                    if (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            info = (TLRPC.ChatParticipants)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();

                    if (info != null) {
                        boolean modified = false;
                        ArrayList<Integer> usersArr = new ArrayList<Integer>();
                        String usersToLoad = "";
                        for (int a = 0; a < info.participants.size(); a++) {
                            TLRPC.TL_chatParticipant c = info.participants.get(a);
                            if (usersArr.contains(c.user_id)) {
                                info.participants.remove(a);
                                modified = true;
                                a--;
                            } else {
                                if (usersToLoad.length() != 0) {
                                    usersToLoad += ",";
                                }
                                usersArr.add(c.user_id);
                                usersToLoad += c.user_id;
                            }
                        }
                        if (usersToLoad.length() != 0) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", usersToLoad));
                            while (cursor.next()) {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    loadedUsers.add(user);
                                    if (user.status != null) {
                                        user.status.expires = cursor.intValue(1);
                                    }
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            }
                            cursor.dispose();
                        }
                        if (modified) {
                            updateChatInfo(chat_id, info, false);
                        }
                    }
                    if (semaphore != null) {
                        semaphore.release();
                    }
                    MessagesController.getInstance().processChatInfo(chat_id, info, loadedUsers, true);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (semaphore != null) {
                        semaphore.release();
                    }
                }
            }
        });
    }

    public void processPendingRead(final long dialog_id, final int max_id, final int max_date, final boolean delete) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (delete) {
                        //database.executeFast("DELETE FROM pending_read WHERE uid = " + dialog_id).stepThis().dispose();
                    } else {
                        database.beginTransaction();
                        SQLitePreparedStatement state;/* = database.executeFast("REPLACE INTO pending_read VALUES(?, ?)");
                        state.requery();
                        state.bindLong(1, dialog_id);
                        state.bindInteger(2, max_id);
                        state.step();
                        state.dispose();*/

                        int lower_id = (int)dialog_id;

                        if (lower_id != 0) {
                            state = database.executeFast("UPDATE messages SET read_state = 1 WHERE uid = ? AND mid <= ? AND read_state = 0 AND out = 0");
                            state.requery();
                            state.bindLong(1, dialog_id);
                            state.bindInteger(2, max_id);
                            state.step();
                            state.dispose();
                        } else {
                            state = database.executeFast("UPDATE messages SET read_state = 1 WHERE uid = ? AND date <= ? AND read_state = 0 AND out = 0");
                            state.requery();
                            state.bindLong(1, dialog_id);
                            state.bindInteger(2, max_date);
                            state.step();
                            state.dispose();
                        }

                        state = database.executeFast("UPDATE dialogs SET unread_count = 0 WHERE did = ?");
                        state.requery();
                        state.bindLong(1, dialog_id);
                        state.step();
                        state.dispose();

                        database.commitTransaction();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void searchDialogs(final Integer token, final String query, final boolean needEncrypted) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<TLRPC.User> encUsers = new ArrayList<TLRPC.User>();
                    String q = query.trim().toLowerCase();
                    if (q.length() == 0) {
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.reloadSearchResults, token, new ArrayList<TLObject>(), new ArrayList<CharSequence>(), new ArrayList<CharSequence>());
                        return;
                    }
                    ArrayList<TLObject> resultArray = new ArrayList<TLObject>();
                    ArrayList<CharSequence> resultArrayNames = new ArrayList<CharSequence>();

                    SQLiteCursor cursor = database.queryFinalized("SELECT u.data, u.status, u.name FROM users as u INNER JOIN contacts as c ON u.uid = c.uid");
                    while (cursor.next()) {
                        String name = cursor.stringValue(2);
                        if (name.startsWith(q) || name.contains(" " + q)) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                if (user.id != UserConfig.getClientUserId()) {
                                    if (user.status != null) {
                                        user.status.expires = cursor.intValue(1);
                                    }
                                    resultArrayNames.add(Utilities.generateSearchName(user.first_name, user.last_name, q));
                                    resultArray.add(user);
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                    }
                    cursor.dispose();

                    if (needEncrypted) {
                        cursor = database.queryFinalized("SELECT q.data, q.name, q.user, q.g, q.authkey, q.ttl, u.data, u.status FROM enc_chats as q INNER JOIN dialogs as d ON (q.uid << 32) = d.did INNER JOIN users as u ON q.user = u.uid");
                        while (cursor.next()) {
                            String name = cursor.stringValue(1);
                            if (name.startsWith(q) || name.contains(" " + q)) {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                ByteBufferDesc data2 = buffersStorage.getFreeBuffer(cursor.byteArrayLength(6));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0 && cursor.byteBufferValue(6, data2.buffer) != 0) {
                                    TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    chat.user_id = cursor.intValue(2);
                                    chat.a_or_b = cursor.byteArrayValue(3);
                                    chat.auth_key = cursor.byteArrayValue(4);
                                    chat.ttl = cursor.intValue(5);

                                    TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data2, data2.readInt32());
                                    if (user.status != null) {
                                        user.status.expires = cursor.intValue(7);
                                    }
                                    resultArrayNames.add(Html.fromHtml("<font color=\"#00a60e\">" + ContactsController.formatName(user.first_name, user.last_name) + "</font>"));
                                    resultArray.add(chat);
                                    encUsers.add(user);
                                }
                                buffersStorage.reuseFreeBuffer(data);
                                buffersStorage.reuseFreeBuffer(data2);
                            }
                        }
                        cursor.dispose();
                    }

                    cursor = database.queryFinalized("SELECT data, name FROM chats");
                    while (cursor.next()) {
                        String name = cursor.stringValue(1);
                        String[] args = name.split(" ");
                        if (name.startsWith(q) || name.contains(" " + q)) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.Chat chat = (TLRPC.Chat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                if (!needEncrypted && chat.id < 0) {
                                    continue;
                                }
                                resultArrayNames.add(Utilities.generateSearchName(chat.title, null, q));
                                resultArray.add(chat);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                    }
                    cursor.dispose();

                    NotificationCenter.getInstance().postNotificationName(NotificationCenter.reloadSearchResults, token, resultArray, resultArrayNames, encUsers);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putContacts(final ArrayList<TLRPC.TL_contact> contacts, final boolean deleteAll) {
        if (contacts.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (deleteAll) {
                        database.executeFast("DELETE FROM contacts WHERE 1").stepThis().dispose();
                    }
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO contacts VALUES(?, ?)");
                    for (TLRPC.TL_contact contact : contacts) {
                        state.requery();
                        state.bindInteger(1, contact.user_id);
                        state.bindInteger(2, contact.mutual ? 1 : 0);
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteContacts(final ArrayList<Integer> uids) {
        if (uids == null || uids.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String ids = "";
                    for (Integer uid : uids) {
                        if (ids.length() != 0) {
                            ids += ",";
                        }
                        ids += "" + uid;
                    }
                    database.executeFast("DELETE FROM contacts WHERE uid IN(" + ids + ")").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void applyPhoneBookUpdates(final String adds, final String deletes) {
        if (adds.length() == 0 && deletes.length() == 0) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (adds.length() != 0) {
                        database.executeFast(String.format(Locale.US, "UPDATE user_phones_v6 SET deleted = 0 WHERE sphone IN(%s)", adds)).stepThis().dispose();
                    }
                    if (deletes.length() != 0) {
                        database.executeFast(String.format(Locale.US, "UPDATE user_phones_v6 SET deleted = 1 WHERE sphone IN(%s)", deletes)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putCachedPhoneBook(final HashMap<Integer, ContactsController.Contact> contactHashMap) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO user_contacts_v6 VALUES(?, ?, ?)");
                    SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO user_phones_v6 VALUES(?, ?, ?, ?)");
                    for (HashMap.Entry<Integer, ContactsController.Contact> entry : contactHashMap.entrySet()) {
                        ContactsController.Contact contact = entry.getValue();
                        if (contact.phones.isEmpty() || contact.shortPhones.isEmpty()) {
                            continue;
                        }
                        state.requery();
                        state.bindInteger(1, contact.id);
                        state.bindString(2, contact.first_name);
                        state.bindString(3, contact.last_name);
                        state.step();
                        for (int a = 0; a < contact.phones.size(); a++) {
                            state2.requery();
                            state2.bindInteger(1, contact.id);
                            state2.bindString(2, contact.phones.get(a));
                            state2.bindString(3, contact.shortPhones.get(a));
                            state2.bindInteger(4, contact.phoneDeleted.get(a));
                            state2.step();
                        }
                    }
                    state.dispose();
                    state2.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getCachedPhoneBook() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                HashMap<Integer, ContactsController.Contact> contactHashMap = new HashMap<Integer, ContactsController.Contact>();
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT us.uid, us.fname, us.sname, up.phone, up.sphone, up.deleted FROM user_contacts_v6 as us LEFT JOIN user_phones_v6 as up ON us.uid = up.uid WHERE 1");
                    while (cursor.next()) {
                        int uid = cursor.intValue(0);
                        ContactsController.Contact contact = contactHashMap.get(uid);
                        if (contact == null) {
                            contact = new ContactsController.Contact();
                            contact.first_name = cursor.stringValue(1);
                            contact.last_name = cursor.stringValue(2);
                            contact.id = uid;
                            contactHashMap.put(uid, contact);
                        }
                        String phone = cursor.stringValue(3);
                        if (phone == null) {
                            continue;
                        }
                        contact.phones.add(phone);
                        String sphone = cursor.stringValue(4);
                        if (sphone == null) {
                            continue;
                        }
                        if (sphone.length() == 8 && phone.length() != 8) {
                            sphone = PhoneFormat.stripExceptNumbers(phone);
                        }
                        contact.shortPhones.add(sphone);
                        contact.phoneDeleted.add(cursor.intValue(5));
                        contact.phoneTypes.add("");
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    contactHashMap.clear();
                    FileLog.e("tmessages", e);
                }
                ContactsController.getInstance().performSyncPhoneBook(contactHashMap, true, true, false);
            }
        });
    }

    public void getContacts() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                ArrayList<TLRPC.TL_contact> contacts = new ArrayList<TLRPC.TL_contact>();
                ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT * FROM contacts WHERE 1");
                    String uids = "";
                    while (cursor.next()) {
                        int user_id = cursor.intValue(0);
                        if (user_id == UserConfig.getClientUserId()) {
                            continue;
                        }
                        TLRPC.TL_contact contact = new TLRPC.TL_contact();
                        contact.user_id = user_id;
                        contact.mutual = cursor.intValue(1) == 1;
                        if (uids.length() != 0) {
                            uids += ",";
                        }
                        contacts.add(contact);
                        uids += contact.user_id;
                    }
                    cursor.dispose();

                    if (uids.length() != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", uids));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                users.add(user);
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    contacts.clear();
                    users.clear();
                    FileLog.e("tmessages", e);
                }
                ContactsController.getInstance().processLoadedContacts(contacts, users, 1);
            }
        });
    }

    public void putMediaCount(final long uid, final int count) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO media_counts VALUES(?, ?)");
                    state2.requery();
                    state2.bindLong(1, uid);
                    state2.bindInteger(2, count);
                    state2.step();
                    state2.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getMediaCount(final long uid, final int classGuid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int count = -1;
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT count FROM media_counts WHERE uid = %d LIMIT 1", uid));
                    if (cursor.next()) {
                        count = cursor.intValue(0);
                    }
                    cursor.dispose();
                    int lower_part = (int)uid;
                    if (count == -1 && lower_part == 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(mid) FROM media WHERE uid = %d LIMIT 1", uid));
                        if (cursor.next()) {
                            count = cursor.intValue(0);
                        }
                        cursor.dispose();
                        if (count != -1) {
                            putMediaCount(uid, count);
                        }
                    }
                    MessagesController.getInstance().processLoadedMediaCount(count, uid, classGuid, true);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadMedia(final long uid, final int offset, final int count, final int max_id, final int classGuid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
                try {
                    ArrayList<Integer> loadedUsers = new ArrayList<Integer>();
                    ArrayList<Integer> fromUser = new ArrayList<Integer>();

                    SQLiteCursor cursor;

                    if ((int)uid != 0) {
                        if (max_id != 0) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid FROM media WHERE uid = %d AND mid < %d ORDER BY date DESC, mid DESC LIMIT %d", uid, max_id, count));
                        } else {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid FROM media WHERE uid = %d ORDER BY date DESC, mid DESC LIMIT %d,%d", uid, offset, count));
                        }
                    } else {
                        if (max_id != 0) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid FROM media WHERE uid = %d AND mid > %d ORDER BY mid ASC LIMIT %d", uid, max_id, count));
                        } else {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid FROM media WHERE uid = %d ORDER BY mid ASC LIMIT %d,%d", uid, offset, count));
                        }
                    }

                    while (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            message.id = cursor.intValue(1);
                            message.dialog_id = uid;
                            res.messages.add(message);
                            fromUser.add(message.from_id);
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();

                    String usersToLoad = "";
                    for (int uid : fromUser) {
                        if (!loadedUsers.contains(uid)) {
                            if (usersToLoad.length() != 0) {
                                usersToLoad += ",";
                            }
                            usersToLoad += uid;
                            loadedUsers.add(uid);
                        }
                    }
                    if (usersToLoad.length() != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", usersToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                loadedUsers.add(user.id);
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                                res.users.add(user);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    res.messages.clear();
                    res.chats.clear();
                    res.users.clear();
                    FileLog.e("tmessages", e);
                } finally {
                    MessagesController.getInstance().processLoadedMedia(res, uid, offset, count, max_id, true, classGuid);
                }
            }
        });
    }

    public void putMedia(final long uid, final ArrayList<TLRPC.Message> messages) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO media VALUES(?, ?, ?, ?)");
                    for (TLRPC.Message message : messages) {
                        if (message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaPhoto) {
                            state2.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(message.getObjectSize());
                            message.serializeToStream(data);
                            state2.bindInteger(1, message.id);
                            state2.bindLong(2, uid);
                            state2.bindInteger(3, message.date);
                            state2.bindByteBuffer(4, data.buffer);
                            state2.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                    }
                    state2.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getUnsentMessages(final int count) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<TLRPC.Message> messages = new ArrayList<TLRPC.Message>();
                    ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
                    ArrayList<TLRPC.Chat> chats = new ArrayList<TLRPC.Chat>();
                    ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<TLRPC.EncryptedChat>();

                    ArrayList<Integer> userIds = new ArrayList<Integer>();
                    ArrayList<Integer> chatIds = new ArrayList<Integer>();
                    ArrayList<Integer> broadcastIds = new ArrayList<Integer>();
                    ArrayList<Integer> encryptedChatIds = new ArrayList<Integer>();
                    SQLiteCursor cursor = database.queryFinalized("SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.uid FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.mid < 0 AND m.send_state = 1 ORDER BY m.mid DESC LIMIT " + count);
                    while (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(1));
                        if (data != null && cursor.byteBufferValue(1, data.buffer) != 0) {
                            TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            int read_state = cursor.intValue(0);
                            message.unread = (cursor.intValue(0) != 1);
                            message.id = cursor.intValue(3);
                            message.date = cursor.intValue(4);
                            if (!cursor.isNull(5)) {
                                message.random_id = cursor.longValue(5);
                            }
                            message.dialog_id = cursor.longValue(6);
                            messages.add(message);

                            int lower_id = (int)message.dialog_id;
                            int high_id = (int)(message.dialog_id >> 32);

                            if (lower_id != 0) {
                                if (high_id == 1) {
                                    if (!broadcastIds.contains(lower_id)) {
                                        broadcastIds.add(lower_id);
                                    }
                                } else {
                                    if (lower_id < 0) {
                                        if (!chatIds.contains(-lower_id)) {
                                            chatIds.add(-lower_id);
                                        }
                                    } else {
                                        if (!userIds.contains(lower_id)) {
                                            userIds.add(lower_id);
                                        }
                                    }
                                }
                            } else {
                                if (!encryptedChatIds.contains(high_id)) {
                                    encryptedChatIds.add(high_id);
                                }
                            }

                            if (!userIds.contains(message.from_id)) {
                                userIds.add(message.from_id);
                            }
                            if (message.action != null && message.action.user_id != 0 && !userIds.contains(message.action.user_id)) {
                                userIds.add(message.action.user_id);
                            }
                            if (message.media != null && message.media.user_id != 0 && !userIds.contains(message.media.user_id)) {
                                userIds.add(message.media.user_id);
                            }
                            if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0 && !userIds.contains(message.media.audio.user_id)) {
                                userIds.add(message.media.audio.user_id);
                            }
                            if (message.fwd_from_id != 0 && !userIds.contains(message.fwd_from_id)) {
                                userIds.add(message.fwd_from_id);
                            }
                            message.send_state = cursor.intValue(2);
                            if (!message.unread && lower_id != 0 || message.id > 0) {
                                message.send_state = 0;
                            }
                            if (lower_id == 0 && !cursor.isNull(5)) {
                                message.random_id = cursor.longValue(5);
                            }
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();

                    String stringToLoad = "";
                    if (!encryptedChatIds.isEmpty()) {
                        for (int uid : encryptedChatIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad += ",";
                            }
                            stringToLoad += uid;
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid IN(%s)", stringToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                encryptedChats.add(chat);
                                chat.user_id = cursor.intValue(1);
                                if (!userIds.contains(chat.user_id)) {
                                    userIds.add(chat.user_id);
                                }
                                chat.a_or_b = cursor.byteArrayValue(2);
                                chat.auth_key = cursor.byteArrayValue(3);
                                chat.ttl = cursor.intValue(4);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }


                    if (!userIds.isEmpty()) {
                        stringToLoad = "";
                        for (Integer uid : userIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad += ",";
                            }
                            stringToLoad += uid;
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", stringToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                                users.add(user);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }

                    if (!chatIds.isEmpty() || !broadcastIds.isEmpty()) {
                        stringToLoad = "";
                        for (Integer cid : chatIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad += ",";
                            }
                            stringToLoad += cid;
                        }
                        for (Integer cid : broadcastIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad += ",";
                            }
                            stringToLoad += (-cid);
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid IN(%s)", stringToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.Chat chat = (TLRPC.Chat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                chats.add(chat);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }

                    SendMessagesHelper.getInstance().processUnsentMessages(messages, users, chats, encryptedChats);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getMessages(final long dialog_id, final int count, final int max_id, final int minDate, final int classGuid, final boolean from_unread, final boolean forward) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
                int count_unread = 0;
                int count_query = count;
                int offset_query = 0;
                int min_unread_id = 0;
                int max_unread_id = 0;
                int max_unread_date = 0;
                try {
                    ArrayList<Integer> loadedUsers = new ArrayList<Integer>();
                    ArrayList<Integer> fromUser = new ArrayList<Integer>();

                    SQLiteCursor cursor;
                    int lower_id = (int)dialog_id;

                    if (lower_id != 0) {
                        if (forward) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT read_state, data, send_state, mid, date FROM messages WHERE uid = %d AND date >= %d AND mid > %d ORDER BY date ASC, mid ASC LIMIT %d", dialog_id, minDate, max_id, count_query));
                        } else if (minDate != 0) {
                            if (max_id != 0) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT read_state, data, send_state, mid, date FROM messages WHERE uid = %d AND date <= %d AND mid < %d ORDER BY date DESC, mid DESC LIMIT %d", dialog_id, minDate, max_id, count_query));
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT read_state, data, send_state, mid, date FROM messages WHERE uid = %d AND date <= %d ORDER BY date DESC, mid DESC LIMIT %d,%d", dialog_id, minDate, offset_query, count_query));
                            }
                        } else {
                            if (from_unread) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(mid), max(mid), max(date) FROM messages WHERE uid = %d AND out = 0 AND read_state = 0 AND mid > 0", dialog_id));
                                if (cursor.next()) {
                                    min_unread_id = cursor.intValue(0);
                                    max_unread_id = cursor.intValue(1);
                                    max_unread_date = cursor.intValue(2);
                                }
                                cursor.dispose();
                                if (min_unread_id != 0) {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(*) FROM messages WHERE uid = %d AND mid >= %d AND out = 0 AND read_state = 0", dialog_id, min_unread_id));
                                    if (cursor.next()) {
                                        count_unread = cursor.intValue(0);
                                    }
                                    cursor.dispose();
                                }
                            }

                            if (count_query > count_unread || count_unread < 4) {
                                count_query = Math.max(count_query, count_unread + 10);
                                if (count_unread < 4) {
                                    count_unread = 0;
                                    min_unread_id = 0;
                                    max_unread_id = 0;
                                }
                            } else {
                                offset_query = count_unread - count_query;
                                count_query += 10;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT read_state, data, send_state, mid, date FROM messages WHERE uid = %d ORDER BY date DESC, mid DESC LIMIT %d,%d", dialog_id, offset_query, count_query));
                        }
                    } else {
                        if (forward) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid < %d ORDER BY m.mid DESC LIMIT %d", dialog_id, max_id, count_query));
                        } else if (minDate != 0) {
                            if (max_id != 0) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid > %d ORDER BY m.mid ASC LIMIT %d", dialog_id, max_id, count_query));
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date <= %d ORDER BY m.mid ASC LIMIT %d,%d", dialog_id, minDate, offset_query, count_query));
                            }
                        } else {
                            if (from_unread) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT max(mid), min(mid), max(date) FROM messages WHERE uid = %d AND out = 0 AND read_state = 0 AND mid < 0", dialog_id));
                                if (cursor.next()) {
                                    min_unread_id = cursor.intValue(0);
                                    max_unread_id = cursor.intValue(1);
                                    max_unread_date = cursor.intValue(2);
                                }
                                cursor.dispose();
                                if (min_unread_id != 0) {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(*) FROM messages WHERE uid = %d AND mid <= %d AND out = 0 AND read_state = 0", dialog_id, min_unread_id));
                                    if (cursor.next()) {
                                        count_unread = cursor.intValue(0);
                                    }
                                    cursor.dispose();
                                }
                            }

                            if (count_query > count_unread || count_unread < 4) {
                                count_query = Math.max(count_query, count_unread + 10);
                                if (count_unread < 4) {
                                    count_unread = 0;
                                    min_unread_id = 0;
                                    max_unread_id = 0;
                                }
                            } else {
                                offset_query = count_unread - count_query;
                                count_query += 10;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d ORDER BY m.mid ASC LIMIT %d,%d", dialog_id, offset_query, count_query));
                        }
                    }
                    while (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(1));
                        if (data != null && cursor.byteBufferValue(1, data.buffer) != 0) {
                            TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            int read_state = cursor.intValue(0);
                            message.unread = (cursor.intValue(0) != 1);
                            message.id = cursor.intValue(3);
                            message.date = cursor.intValue(4);
                            message.dialog_id = dialog_id;
                            res.messages.add(message);
                            fromUser.add(message.from_id);
                            if (message.action != null && message.action.user_id != 0) {
                                fromUser.add(message.action.user_id);
                            }
                            if (message.media != null && message.media.user_id != 0) {
                                fromUser.add(message.media.user_id);
                            }
                            if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0) {
                                fromUser.add(message.media.audio.user_id);
                            }
                            if (message.fwd_from_id != 0) {
                                fromUser.add(message.fwd_from_id);
                            }
                            message.send_state = cursor.intValue(2);
                            if (!message.unread && lower_id != 0 || message.id > 0) {
                                message.send_state = 0;
                            }
                            if (lower_id == 0 && !cursor.isNull(5)) {
                                message.random_id = cursor.longValue(5);
                            }
                        }
                        buffersStorage.reuseFreeBuffer(data);

                        Collections.sort(res.messages, new Comparator<TLRPC.Message>() {
                            @Override
                            public int compare(TLRPC.Message lhs, TLRPC.Message rhs) {
                                if (lhs.id > 0 && rhs.id > 0) {
                                    if (!forward) {
                                        if (lhs.id > rhs.id) {
                                            return -1;
                                        } else if (lhs.id < rhs.id) {
                                            return 1;
                                        }
                                    } else {
                                        if (lhs.id < rhs.id) {
                                            return -1;
                                        } else if (lhs.id > rhs.id) {
                                            return 1;
                                        }
                                    }
                                } else if (lhs.id < 0 && rhs.id < 0) {
                                    if (!forward) {
                                        if (lhs.id < rhs.id) {
                                            return -1;
                                        } else if (lhs.id > rhs.id) {
                                            return 1;
                                        }
                                    } else {
                                        if (lhs.id > rhs.id) {
                                            return -1;
                                        } else if (lhs.id < rhs.id) {
                                            return 1;
                                        }
                                    }
                                } else {
                                    if (!forward) {
                                        if (lhs.date > rhs.date) {
                                            return -1;
                                        } else if (lhs.date < rhs.date) {
                                            return 1;
                                        }
                                    } else {
                                        if (lhs.date < rhs.date) {
                                            return -1;
                                        } else if (lhs.date > rhs.date) {
                                            return 1;
                                        }
                                    }
                                }
                                return 0;
                            }
                        });
                    }
                    cursor.dispose();

                    String usersToLoad = "";
                    for (int uid : fromUser) {
                        if (!loadedUsers.contains(uid)) {
                            if (usersToLoad.length() != 0) {
                                usersToLoad += ",";
                            }
                            usersToLoad += uid;
                            loadedUsers.add(uid);
                        }
                    }
                    if (usersToLoad.length() != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", usersToLoad));
                        while (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                loadedUsers.add(user.id);
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                                res.users.add(user);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    res.messages.clear();
                    res.chats.clear();
                    res.users.clear();
                    FileLog.e("tmessages", e);
                } finally {
                    MessagesController.getInstance().processLoadedMessages(res, dialog_id, count_query, max_id, true, classGuid, min_unread_id, max_unread_id, count_unread, max_unread_date, forward);
                }
            }
        });
    }

    public void startTransaction(boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        database.beginTransaction();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            });
        } else {
            try {
                database.beginTransaction();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
    }

    public void commitTransaction(boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    database.commitTransaction();
                }
            });
        } else {
            database.commitTransaction();
        }
    }

    public TLObject getSentFile(final String path, final int type) {
        if (path == null) {
            return null;
        }
        final Semaphore semaphore = new Semaphore(0);
        final ArrayList<TLObject> result = new ArrayList<TLObject>();
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String id = Utilities.MD5(path);
                    if (id != null) {
                        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM sent_files_v2 WHERE uid = '%s' AND type = %d", id, type));
                        if (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLObject file = TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                if (file != null) {
                                    result.add(file);
                                }
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    semaphore.release();
                }
            }
        });
        try {
            semaphore.acquire();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return !result.isEmpty() ? result.get(0) : null;
    }

    public void putSentFile(final String path, final TLObject file, final int type) {
        if (path == null || file == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String id = Utilities.MD5(path);
                    if (id != null) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO sent_files_v2 VALUES(?, ?, ?)");
                        state.requery();
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(file.getObjectSize());
                        file.serializeToStream(data);
                        state.bindString(1, id);
                        state.bindInteger(2, type);
                        state.bindByteBuffer(3, data.buffer);
                        state.step();
                        state.dispose();
                        buffersStorage.reuseFreeBuffer(data);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void updateEncryptedChatTTL(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    state = database.executeFast("UPDATE enc_chats SET ttl = ? WHERE uid = ?");
                    state.bindInteger(1, chat.ttl);
                    state.bindInteger(2, chat.id);
                    state.step();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void updateEncryptedChat(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    state = database.executeFast("UPDATE enc_chats SET data = ?, g = ?, authkey = ?, ttl = ? WHERE uid = ?");
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(chat.getObjectSize());
                    ByteBufferDesc data2 = buffersStorage.getFreeBuffer(chat.a_or_b != null ? chat.a_or_b.length : 1);
                    ByteBufferDesc data3 = buffersStorage.getFreeBuffer(chat.auth_key != null ? chat.auth_key.length : 1);
                    chat.serializeToStream(data);
                    state.bindByteBuffer(1, data.buffer);
                    if (chat.a_or_b != null) {
                        data2.writeRaw(chat.a_or_b);
                    }
                    if (chat.auth_key != null) {
                        data3.writeRaw(chat.auth_key);
                    }
                    state.bindByteBuffer(2, data2.buffer);
                    state.bindByteBuffer(3, data3.buffer);
                    state.bindInteger(4, chat.ttl);
                    state.bindInteger(5, chat.id);
                    state.step();
                    buffersStorage.reuseFreeBuffer(data);
                    buffersStorage.reuseFreeBuffer(data2);
                    buffersStorage.reuseFreeBuffer(data3);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void getEncryptedChat(final int chat_id, final Semaphore semaphore, final ArrayList<TLObject> result) {
        if (semaphore == null || result == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int userToLoad = 0;
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid = %d", chat_id));
                    if (cursor.next()) {
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                            TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            result.add(chat);
                            chat.user_id = cursor.intValue(1);
                            userToLoad = chat.user_id;
                            chat.a_or_b = cursor.byteArrayValue(2);
                            chat.auth_key = cursor.byteArrayValue(3);
                            chat.ttl = cursor.intValue(4);
                        }
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    cursor.dispose();
                    if (userToLoad != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid = %d", userToLoad));
                        if (cursor.next()) {
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                if (user.status != null) {
                                    user.status.expires = cursor.intValue(1);
                                }
                                result.add(user);
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        cursor.dispose();

                        if (result.size() != 2) {
                            result.clear();
                        }
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    semaphore.release();
                }
            }
        });
    }

    public void putEncryptedChat(final TLRPC.EncryptedChat chat, final TLRPC.User user, final TLRPC.TL_dialog dialog) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO enc_chats VALUES(?, ?, ?, ?, ?, ?, ?)");
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(chat.getObjectSize());
                    ByteBufferDesc data2 = buffersStorage.getFreeBuffer(chat.a_or_b != null ? chat.a_or_b.length : 1);
                    ByteBufferDesc data3 = buffersStorage.getFreeBuffer(chat.auth_key != null ? chat.auth_key.length : 1);

                    chat.serializeToStream(data);
                    state.bindInteger(1, chat.id);
                    state.bindInteger(2, user.id);
                    if (user.first_name != null && user.last_name != null) {
                        String name = (user.first_name + " " + user.last_name).toLowerCase();
                        state.bindString(3, name);
                    } else {
                        state.bindString(3, "");
                    }
                    state.bindByteBuffer(4, data.buffer);
                    if (chat.a_or_b != null) {
                        data2.writeRaw(chat.a_or_b);
                    }
                    if (chat.auth_key != null) {
                        data3.writeRaw(chat.auth_key);
                    }
                    state.bindByteBuffer(5, data2.buffer);
                    state.bindByteBuffer(6, data3.buffer);
                    state.bindInteger(7, chat.ttl);
                    state.step();
                    state.dispose();
                    buffersStorage.reuseFreeBuffer(data);
                    buffersStorage.reuseFreeBuffer(data2);
                    buffersStorage.reuseFreeBuffer(data3);

                    if (dialog != null) {
                        state = database.executeFast("REPLACE INTO dialogs(did, date, unread_count, last_mid) VALUES(?, ?, ?, ?)");
                        state.bindLong(1, dialog.id);
                        state.bindInteger(2, dialog.last_message_date);
                        state.bindInteger(3, dialog.unread_count);
                        state.bindInteger(4, dialog.top_message);
                        state.step();
                        state.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void putUsersAndChatsInternal(final ArrayList<TLRPC.User> users, final ArrayList<TLRPC.Chat> chats, final boolean withTransaction) {
        try {
            if (withTransaction) {
                database.beginTransaction();
            }
            if (users != null && !users.isEmpty()) {
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO users VALUES(?, ?, ?, ?)");
                for (TLRPC.User user : users) {
                    state.requery();
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(user.getObjectSize());
                    user.serializeToStream(data);
                    state.bindInteger(1, user.id);
                    if (user.first_name != null && user.last_name != null) {
                        String name = (user.first_name + " " + user.last_name).toLowerCase();
                        state.bindString(2, name);
                    } else {
                        state.bindString(2, "");
                    }
                    if (user.status != null) {
                        state.bindInteger(3, user.status.expires);
                    } else {
                        state.bindInteger(3, 0);
                    }
                    state.bindByteBuffer(4, data.buffer);
                    state.step();
                    buffersStorage.reuseFreeBuffer(data);
                }
                state.dispose();
            }
            if (chats != null && !chats.isEmpty()) {
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO chats VALUES(?, ?, ?)");
                for (TLRPC.Chat chat : chats) {
                    state.requery();
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(chat.getObjectSize());
                    chat.serializeToStream(data);
                    state.bindInteger(1, chat.id);
                    if (chat.title != null) {
                        String name = chat.title.toLowerCase();
                        state.bindString(2, name);
                    } else {
                        state.bindString(2, "");
                    }
                    state.bindByteBuffer(3, data.buffer);
                    state.step();
                    buffersStorage.reuseFreeBuffer(data);
                }
                state.dispose();
            }
            if (withTransaction) {
                database.commitTransaction();
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void putUsersAndChats(final ArrayList<TLRPC.User> users, final ArrayList<TLRPC.Chat> chats, final boolean withTransaction, boolean useQueue) {
        if (users != null && users.isEmpty() && chats != null && chats.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    putUsersAndChatsInternal(users, chats, withTransaction);
                }
            });
        } else {
            putUsersAndChatsInternal(users, chats, withTransaction);
        }
    }

    public void removeFromDownloadQueue(final long id, final int type, final boolean move) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (move) {
                        int minDate = -1;
                        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(date) FROM download_queue WHERE type = %d", type));
                        if (cursor.next()) {
                            minDate = cursor.intValue(0);
                        }
                        cursor.dispose();
                        if (minDate != -1) {
                            database.executeFast(String.format(Locale.US, "UPDATE download_queue SET date = %d WHERE uid = %d AND type = %d", minDate - 1, id, type)).stepThis().dispose();
                        }
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM download_queue WHERE uid = %d AND type = %d", id, type)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearDownloadQueue(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (type == 0) {
                        database.executeFast("DELETE FROM download_queue WHERE 1").stepThis().dispose();
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM download_queue WHERE type = %d", type)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getDownloadQueue(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<DownloadObject> objects = new ArrayList<DownloadObject>();
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, type, data FROM download_queue WHERE type = %d ORDER BY date DESC LIMIT 3", type));
                    while (cursor.next()) {
                        DownloadObject downloadObject = new DownloadObject();
                        downloadObject.type = cursor.intValue(1);
                        downloadObject.id = cursor.longValue(0);
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(2));
                        if (data != null && cursor.byteBufferValue(2, data.buffer) != 0) {
                            downloadObject.object = TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        }
                        buffersStorage.reuseFreeBuffer(data);
                        objects.add(downloadObject);
                    }
                    cursor.dispose();

                    AndroidUtilities.RunOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            MediaController.getInstance().processDownloadObjects(type, objects);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void putMessagesInternal(final ArrayList<TLRPC.Message> messages, final boolean withTransaction, final boolean isBroadcast, final int downloadMask) {
        try {
            if (withTransaction) {
                database.beginTransaction();
            }
            HashMap<Long, TLRPC.Message> messagesMap = new HashMap<Long, TLRPC.Message>();
            HashMap<Long, Integer> messagesCounts = new HashMap<Long, Integer>();
            HashMap<Long, Integer> mediaCounts = new HashMap<Long, Integer>();
            HashMap<Integer, Long> messagesIdsMap = new HashMap<Integer, Long>();
            HashMap<Integer, Long> messagesMediaIdsMap = new HashMap<Integer, Long>();
            String messageIds = "";
            String messageMediaIds = "";
            SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
            SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO media VALUES(?, ?, ?, ?)");
            SQLitePreparedStatement state3 = database.executeFast("REPLACE INTO randoms VALUES(?, ?)");
            SQLitePreparedStatement state4 = database.executeFast("REPLACE INTO download_queue VALUES(?, ?, ?, ?)");

            for (TLRPC.Message message : messages) {
                long dialog_id = message.dialog_id;
                if (dialog_id == 0) {
                    if (message.to_id.chat_id != 0) {
                        dialog_id = -message.to_id.chat_id;
                    } else if (message.to_id.user_id != 0) {
                        dialog_id = message.to_id.user_id;
                    }
                }

                if (message.unread && !message.out) {
                    if (messageIds.length() > 0) {
                        messageIds += ",";
                    }
                    messageIds += message.id;
                    messagesIdsMap.put(message.id, dialog_id);
                }

                if (message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaPhoto) {
                    if (messageMediaIds.length() > 0) {
                        messageMediaIds += ",";
                    }
                    messageMediaIds += message.id;
                    messagesMediaIdsMap.put(message.id, dialog_id);
                }
            }

            if (messageIds.length() > 0) {
                SQLiteCursor cursor = database.queryFinalized("SELECT mid FROM messages WHERE mid IN(" + messageIds + ")");
                while (cursor.next()) {
                    int mid = cursor.intValue(0);
                    messagesIdsMap.remove(mid);
                }
                cursor.dispose();
                for (Long dialog_id : messagesIdsMap.values()) {
                    Integer count = messagesCounts.get(dialog_id);
                    if (count == null) {
                        count = 0;
                    }
                    count++;
                    messagesCounts.put(dialog_id, count);
                }
            }

            if (messageMediaIds.length() > 0) {
                SQLiteCursor cursor = database.queryFinalized("SELECT mid FROM media WHERE mid IN(" + messageMediaIds + ")");
                while (cursor.next()) {
                    int mid = cursor.intValue(0);
                    messagesMediaIdsMap.remove(mid);
                }
                cursor.dispose();
                for (Long dialog_id : messagesMediaIdsMap.values()) {
                    Integer count = mediaCounts.get(dialog_id);
                    if (count == null) {
                        count = 0;
                    }
                    count++;
                    mediaCounts.put(dialog_id, count);
                }
            }

            int downloadMediaMask = 0;
            for (TLRPC.Message message : messages) {
                long dialog_id = message.dialog_id;
                if (dialog_id == 0) {
                    if (message.to_id.chat_id != 0) {
                        dialog_id = -message.to_id.chat_id;
                    } else if (message.to_id.user_id != 0) {
                        dialog_id = message.to_id.user_id;
                    }
                }

                state.requery();
                int messageId = message.id;
                if (message.local_id != 0) {
                    messageId = message.local_id;
                }

                ByteBufferDesc data = buffersStorage.getFreeBuffer(message.getObjectSize());
                message.serializeToStream(data);
                TLRPC.Message lastMessage = messagesMap.get(dialog_id);
                if (lastMessage == null || message.date > lastMessage.date) {
                    messagesMap.put(dialog_id, message);
                }
                state.bindInteger(1, messageId);
                state.bindLong(2, dialog_id);
                state.bindInteger(3, (message.unread ? 0 : 1));
                state.bindInteger(4, message.send_state);
                state.bindInteger(5, message.date);
                state.bindByteBuffer(6, data.buffer);
                state.bindInteger(7, (message.out ? 1 : 0));
                state.bindInteger(8, message.ttl);
                state.step();

                if (message.random_id != 0) {
                    state3.requery();
                    state3.bindLong(1, message.random_id);
                    state3.bindInteger(2, messageId);
                    state3.step();
                }

                if (message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaPhoto) {
                    state2.requery();
                    state2.bindInteger(1, messageId);
                    state2.bindLong(2, dialog_id);
                    state2.bindInteger(3, message.date);
                    state2.bindByteBuffer(4, data.buffer);
                    state2.step();
                }
                buffersStorage.reuseFreeBuffer(data);

                if (downloadMask != 0) {
                    if (message.media instanceof TLRPC.TL_messageMediaAudio || message.media instanceof TLRPC.TL_messageMediaPhoto || message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaDocument) {
                        int type = 0;
                        long id = 0;
                        TLObject object = null;
                        if (message.media instanceof TLRPC.TL_messageMediaAudio) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_AUDIO) != 0) {
                                id = message.media.audio.id;
                                type = MediaController.AUTODOWNLOAD_MASK_AUDIO;
                                object = message.media.audio;
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_PHOTO) != 0) {
                                TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(message.media.photo.sizes, AndroidUtilities.getPhotoSize());
                                if (photoSize != null) {
                                    id = message.media.photo.id;
                                    type = MediaController.AUTODOWNLOAD_MASK_PHOTO;
                                    object = photoSize;
                                }
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaVideo) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_VIDEO) != 0) {
                                id = message.media.video.id;
                                type = MediaController.AUTODOWNLOAD_MASK_VIDEO;
                                object = message.media.video;
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_DOCUMENT) != 0) {
                                id = message.media.document.id;
                                type = MediaController.AUTODOWNLOAD_MASK_DOCUMENT;
                                object = message.media.document;
                            }
                        }
                        if (object != null) {
                            downloadMediaMask |= type;
                            state4.requery();
                            data = buffersStorage.getFreeBuffer(object.getObjectSize());
                            object.serializeToStream(data);
                            state4.bindLong(1, id);
                            state4.bindInteger(2, type);
                            state4.bindInteger(3, message.date);
                            state4.bindByteBuffer(4, data.buffer);
                            state4.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                    }
                }
            }
            state.dispose();
            state2.dispose();
            state3.dispose();
            state4.dispose();

            state = database.executeFast("REPLACE INTO dialogs(did, date, unread_count, last_mid) VALUES(?, ?, ?, ?)");
            for (HashMap.Entry<Long, TLRPC.Message> pair : messagesMap.entrySet()) {
                Long key = pair.getKey();

                int dialog_date = 0;
                int old_unread_count = 0;
                SQLiteCursor cursor = database.queryFinalized("SELECT date, unread_count FROM dialogs WHERE did = " + key);
                if (cursor.next()) {
                    dialog_date = cursor.intValue(0);
                    old_unread_count = cursor.intValue(1);
                }
                cursor.dispose();

                state.requery();
                TLRPC.Message value = pair.getValue();
                Integer unread_count = messagesCounts.get(key);
                if (unread_count == null) {
                    unread_count = 0;
                } else {
                    messagesCounts.put(key, unread_count + old_unread_count);
                }
                int messageId = value.id;
                if (value.local_id != 0) {
                    messageId = value.local_id;
                }
                state.bindLong(1, key);
                if (!isBroadcast) {
                    state.bindInteger(2, value.date);
                } else {
                    state.bindInteger(2, dialog_date != 0 ? dialog_date : value.date);
                }
                state.bindInteger(3, old_unread_count + unread_count);
                state.bindInteger(4, messageId);
                state.step();
            }
            state.dispose();
            if (withTransaction) {
                database.commitTransaction();
            }
            MessagesController.getInstance().processDialogsUpdateRead(messagesCounts);

            if (!mediaCounts.isEmpty()) {
                state = database.executeFast("REPLACE INTO media_counts VALUES(?, ?)");
                for (HashMap.Entry<Long, Integer> pair : mediaCounts.entrySet()) {
                    long uid = pair.getKey();
                    int lower_part = (int)uid;
                    int count = -1;
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT count FROM media_counts WHERE uid = %d LIMIT 1", uid));
                    if (cursor.next()) {
                        count = cursor.intValue(0);
                    }
                    if (count != -1) {
                        state.requery();
                        count += pair.getValue();
                        state.bindLong(1, uid);
                        state.bindInteger(2, count);
                        state.step();
                    }
                    cursor.dispose();
                }
                state.dispose();
            }

            if (downloadMediaMask != 0) {
                final int downloadMediaMaskFinal = downloadMediaMask;
                AndroidUtilities.RunOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        MediaController.getInstance().newDownloadObjectsAvailable(downloadMediaMaskFinal);
                    }
                });
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void putMessages(final ArrayList<TLRPC.Message> messages, final boolean withTransaction, boolean useQueue, final boolean isBroadcast, final int downloadMask) {
        if (messages.size() == 0) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    putMessagesInternal(messages, withTransaction, isBroadcast, downloadMask);
                }
            });
        } else {
            putMessagesInternal(messages, withTransaction, isBroadcast, downloadMask);
        }
    }

    public void markMessageAsSendError(final int mid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("UPDATE messages SET send_state = 2 WHERE mid = " + mid).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private Integer updateMessageStateAndIdInternal(long random_id, Integer _oldId, int newId, int date) {
        if (_oldId != null && _oldId == newId && date != 0) {
            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("UPDATE messages SET send_state = 0, date = ? WHERE mid = ?");
                state.bindInteger(1, date);
                state.bindInteger(2, newId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }
            return newId;
        } else {
            Integer oldId = _oldId;
            if (oldId == null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM randoms WHERE random_id = %d LIMIT 1", random_id));
                    if (cursor.next()) {
                        oldId = cursor.intValue(0);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
//                if (oldId != null) {
//                    try {
//                        database.executeFast(String.format(Locale.US, "DELETE FROM randoms WHERE random_id = %d", random_id)).stepThis().dispose();
//                    } catch (Exception e) {
//                        FileLog.e("tmessages", e);
//                    }
//                }
            }
            if (oldId == null) {
                return null;
            }

            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("UPDATE messages SET mid = ?, send_state = 0 WHERE mid = ?");
                state.bindInteger(1, newId);
                state.bindInteger(2, oldId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }

            try {
                state = database.executeFast("UPDATE media SET mid = ? WHERE mid = ?");
                state.bindInteger(1, newId);
                state.bindInteger(2, oldId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }

            try {
                state = database.executeFast("UPDATE dialogs SET last_mid = ? WHERE last_mid = ?");
                state.bindInteger(1, newId);
                state.bindLong(2, oldId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }

            return oldId;
        }
    }

    public Integer updateMessageStateAndId(final long random_id, final Integer _oldId, final int newId, final int date, boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateMessageStateAndIdInternal(random_id, _oldId, newId, date);
                }
            });
        } else {
            return updateMessageStateAndIdInternal(random_id, _oldId, newId, date);
        }
        return null;
    }

    private void updateUsersInternal(final ArrayList<TLRPC.User> users, final boolean onlyStatus, final boolean withTransaction) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            if (onlyStatus) {
                if (withTransaction) {
                    database.beginTransaction();
                }
                SQLitePreparedStatement state = database.executeFast("UPDATE users SET status = ? WHERE uid = ?");
                for (TLRPC.User user : users) {
                    state.requery();
                    if (user.status != null) {
                        state.bindInteger(1, user.status.expires);
                    } else {
                        state.bindInteger(1, 0);
                    }
                    state.bindInteger(2, user.id);
                    state.step();
                }
                state.dispose();
                if (withTransaction) {
                    database.commitTransaction();
                }
            } else {
                String ids = "";
                HashMap<Integer, TLRPC.User> usersDict = new HashMap<Integer, TLRPC.User>();
                for (TLRPC.User user : users) {
                    if (ids.length() != 0) {
                        ids += ",";
                    }
                    ids += user.id;
                    usersDict.put(user.id, user);
                }
                ArrayList<TLRPC.User> loadedUsers = new ArrayList<TLRPC.User>();
                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", ids));
                while (cursor.next()) {
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                    if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                        TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        loadedUsers.add(user);
                        if (user.status != null) {
                            user.status.expires = cursor.intValue(1);
                        }
                        TLRPC.User updateUser = usersDict.get(user.id);
                        if (updateUser.first_name != null && updateUser.last_name != null) {
                            user.first_name = updateUser.first_name;
                            user.last_name = updateUser.last_name;
                        } else if (updateUser.photo != null) {
                            user.photo = updateUser.photo;
                        }
                    }
                    buffersStorage.reuseFreeBuffer(data);
                }
                cursor.dispose();
                if (!loadedUsers.isEmpty()) {
                    if (withTransaction) {
                        database.beginTransaction();
                    }
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO users VALUES(?, ?, ?, ?)");
                    for (TLRPC.User user : loadedUsers) {
                        state.requery();
                        ByteBufferDesc data = buffersStorage.getFreeBuffer(user.getObjectSize());
                        user.serializeToStream(data);
                        state.bindInteger(1, user.id);
                        if (user.first_name != null && user.last_name != null) {
                            String name = (user.first_name + " " + user.last_name).toLowerCase();
                            state.bindString(2, name);
                        } else {
                            state.bindString(2, "");
                        }
                        if (user.status != null) {
                            state.bindInteger(3, user.status.expires);
                        } else {
                            state.bindInteger(3, 0);
                        }
                        state.bindByteBuffer(4, data.buffer);
                        state.step();
                        buffersStorage.reuseFreeBuffer(data);
                    }
                    state.dispose();
                    if (withTransaction) {
                        database.commitTransaction();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateUsers(final ArrayList<TLRPC.User> users, final boolean onlyStatus, final boolean withTransaction, boolean useQueue) {
        if (users.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateUsersInternal(users, onlyStatus, withTransaction);
                }
            });
        } else {
            updateUsersInternal(users, onlyStatus, withTransaction);
        }
    }

    private void markMessagesAsReadInternal(final ArrayList<Integer> messages, HashMap<Integer, Integer> encryptedMessages) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            if (messages != null && !messages.isEmpty()) {
                String ids = "";
                for (int uid : messages) {
                    if (ids.length() != 0) {
                        ids += ",";
                    }
                    ids += uid;
                }
                database.executeFast(String.format(Locale.US, "UPDATE messages SET read_state = 1 WHERE mid IN(%s)", ids)).stepThis().dispose();
            }
            if (encryptedMessages != null && !encryptedMessages.isEmpty()) {
                for (HashMap.Entry<Integer, Integer> entry : encryptedMessages.entrySet()) {
                    long dialog_id = ((long)entry.getKey()) << 32;
                    int max_date = entry.getValue();
                    SQLitePreparedStatement state = database.executeFast("UPDATE messages SET read_state = 1 WHERE uid = ? AND date <= ? AND read_state = 0 AND out = 1");
                    state.requery();
                    state.bindLong(1, dialog_id);
                    state.bindInteger(2, max_date);
                    state.step();
                    state.dispose();
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void markMessagesAsRead(final ArrayList<Integer> messages, final HashMap<Integer, Integer> encryptedMessages, boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    markMessagesAsReadInternal(messages, encryptedMessages);
                }
            });
        } else {
            markMessagesAsReadInternal(messages, encryptedMessages);
        }
    }

    public void markMessagesAsDeletedByRandoms(final ArrayList<Long> messages) {
        if (messages.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String ids = "";
                    for (long uid : messages) {
                        if (ids.length() != 0) {
                            ids += ",";
                        }
                        ids += uid;
                    }
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM randoms WHERE random_id IN(%s)", ids));
                    final ArrayList<Integer> mids = new ArrayList<Integer>();
                    while (cursor.next()) {
                        mids.add(cursor.intValue(0));
                    }
                    cursor.dispose();
                    if (!mids.isEmpty()) {
                        AndroidUtilities.RunOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                for (Integer id : mids) {
                                    MessageObject obj = MessagesController.getInstance().dialogMessage.get(id);
                                    if (obj != null) {
                                        obj.deleted = true;
                                    }
                                }
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.messagesDeleted, mids);
                            }
                        });
                        MessagesStorage.getInstance().markMessagesAsDeletedInternal(mids);
                        MessagesStorage.getInstance().updateDialogsWithDeletedMessagesInternal(mids);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void markMessagesAsDeletedInternal(final ArrayList<Integer> messages) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            String ids = "";
            for (int uid : messages) {
                if (ids.length() != 0) {
                    ids += ",";
                }
                ids += uid;
            }
            database.executeFast(String.format(Locale.US, "DELETE FROM messages WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast(String.format(Locale.US, "DELETE FROM media WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast("DELETE FROM media_counts WHERE 1").stepThis().dispose();

        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void updateDialogsWithDeletedMessagesInternal(final ArrayList<Integer> messages) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            String ids = "";
            for (int uid : messages) {
                if (ids.length() != 0) {
                    ids += ",";
                }
                ids += uid;
            }
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT did FROM dialogs WHERE last_mid IN(%s)", ids));
            ArrayList<Long> dialogsToUpdate = new ArrayList<Long>();
            while (cursor.next()) {
                dialogsToUpdate.add(cursor.longValue(0));
            }
            cursor.dispose();
            database.beginTransaction();
            SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET last_mid = (SELECT mid FROM messages WHERE uid = ? AND date = (SELECT MAX(date) FROM messages WHERE uid = ? )) WHERE did = ?");
            for (long did : dialogsToUpdate) {
                state.requery();
                state.bindLong(1, did);
                state.bindLong(2, did);
                state.bindLong(3, did);
                state.step();
            }
            state.dispose();
            database.commitTransaction();

            ids = "";
            for (long uid : dialogsToUpdate) {
                if (ids.length() != 0) {
                    ids += ",";
                }
                ids += uid;
            }

            TLRPC.messages_Dialogs dialogs = new TLRPC.messages_Dialogs();
            ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<TLRPC.EncryptedChat>();
            ArrayList<Integer> usersToLoad = new ArrayList<Integer>();
            ArrayList<Integer> chatsToLoad = new ArrayList<Integer>();
            ArrayList<Integer> encryptedToLoad = new ArrayList<Integer>();
            cursor = database.queryFinalized(String.format(Locale.US, "SELECT d.did, d.last_mid, d.unread_count, d.date, m.data, m.read_state, m.mid, m.send_state FROM dialogs as d LEFT JOIN messages as m ON d.last_mid = m.mid WHERE d.did IN(%s)", ids));
            while (cursor.next()) {
                TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                dialog.id = cursor.longValue(0);
                dialog.top_message = cursor.intValue(1);
                dialog.unread_count = cursor.intValue(2);
                dialog.last_message_date = cursor.intValue(3);
                dialogs.dialogs.add(dialog);

                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(4));
                if (data != null && cursor.byteBufferValue(4, data.buffer) != 0) {
                    TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                    message.unread = (cursor.intValue(5) != 1);
                    message.id = cursor.intValue(6);
                    message.send_state = cursor.intValue(7);
                    dialogs.messages.add(message);

                    if (!usersToLoad.contains(message.from_id)) {
                        usersToLoad.add(message.from_id);
                    }
                    if (message.action != null && message.action.user_id != 0) {
                        if (!usersToLoad.contains(message.action.user_id)) {
                            usersToLoad.add(message.action.user_id);
                        }
                    }
                    if (message.fwd_from_id != 0) {
                        if (!usersToLoad.contains(message.fwd_from_id)) {
                            usersToLoad.add(message.fwd_from_id);
                        }
                    }
                }
                buffersStorage.reuseFreeBuffer(data);

                int lower_id = (int)dialog.id;
                int high_id = (int)(dialog.id >> 32);
                if (lower_id != 0) {
                    if (high_id == 1) {
                        if (!chatsToLoad.contains(lower_id)) {
                            chatsToLoad.add(lower_id);
                        }
                    } else {
                        if (lower_id > 0) {
                            if (!usersToLoad.contains(lower_id)) {
                                usersToLoad.add(lower_id);
                            }
                        } else {
                            if (!chatsToLoad.contains(-lower_id)) {
                                chatsToLoad.add(-lower_id);
                            }
                        }
                    }
                } else {
                    if (!encryptedToLoad.contains(high_id)) {
                        encryptedToLoad.add(high_id);
                    }
                }
            }
            cursor.dispose();

            if (!encryptedToLoad.isEmpty()) {
                String toLoad = "";
                for (int uid : encryptedToLoad) {
                    if (toLoad.length() != 0) {
                        toLoad += ",";
                    }
                    toLoad += uid;
                }
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid IN(%s)", toLoad));
                while (cursor.next()) {
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                    if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                        TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        encryptedChats.add(chat);
                        chat.user_id = cursor.intValue(1);
                        if (!usersToLoad.contains(chat.user_id)) {
                            usersToLoad.add(chat.user_id);
                        }
                        chat.a_or_b = cursor.byteArrayValue(2);
                        chat.auth_key = cursor.byteArrayValue(3);
                        chat.ttl = cursor.intValue(4);
                    }
                    buffersStorage.reuseFreeBuffer(data);
                }
                cursor.dispose();
            }

            if (!chatsToLoad.isEmpty()) {
                String toLoad = "";
                for (int uid : chatsToLoad) {
                    if (toLoad.length() != 0) {
                        toLoad += ",";
                    }
                    toLoad += uid;
                }
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid IN(%s)", toLoad));
                while (cursor.next()) {
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                    if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                        TLRPC.Chat chat = (TLRPC.Chat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        dialogs.chats.add(chat);
                    }
                    buffersStorage.reuseFreeBuffer(data);
                }
                cursor.dispose();
            }

            if (!usersToLoad.isEmpty()) {
                String toLoad = "";
                for (int uid : usersToLoad) {
                    if (toLoad.length() != 0) {
                        toLoad += ",";
                    }
                    toLoad += uid;
                }
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", toLoad));
                while (cursor.next()) {
                    ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                    if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                        TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                        if (user.status != null) {
                            user.status.expires = cursor.intValue(1);
                        }
                        dialogs.users.add(user);
                    }
                    buffersStorage.reuseFreeBuffer(data);
                }
                cursor.dispose();
            }

            if (!dialogs.dialogs.isEmpty() || !encryptedChats.isEmpty()) {
                MessagesController.getInstance().processDialogsUpdate(dialogs, encryptedChats);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateDialogsWithDeletedMessages(final ArrayList<Integer> messages, boolean useQueue) {
        if (messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateDialogsWithDeletedMessagesInternal(messages);
                }
            });
        } else {
            updateDialogsWithDeletedMessagesInternal(messages);
        }
    }

    public void markMessagesAsDeleted(final ArrayList<Integer> messages, boolean useQueue) {
        if (messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    markMessagesAsDeletedInternal(messages);
                }
            });
        } else {
            markMessagesAsDeletedInternal(messages);
        }
    }

    public void putMessages(final TLRPC.messages_Messages messages, final long dialog_id) {
        if (messages.messages.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    if (!messages.messages.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
                        SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO media VALUES(?, ?, ?, ?)");
                        for (TLRPC.Message message : messages.messages) {
                            state.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(message.getObjectSize());
                            message.serializeToStream(data);
                            state.bindInteger(1, message.id);
                            state.bindLong(2, dialog_id);
                            state.bindInteger(3, (message.unread ? 0 : 1));
                            state.bindInteger(4, message.send_state);
                            state.bindInteger(5, message.date);
                            state.bindByteBuffer(6, data.buffer);
                            state.bindInteger(7, (message.out ? 1 : 0));
                            state.bindInteger(8, 0);
                            state.step();

                            if (message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaPhoto) {
                                state2.requery();
                                state2.bindInteger(1, message.id);
                                state2.bindLong(2, dialog_id);
                                state2.bindInteger(3, message.date);
                                state2.bindByteBuffer(4, data.buffer);
                                state2.step();
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                        state2.dispose();
                    }
                    if (!messages.users.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO users VALUES(?, ?, ?, ?)");
                        for (TLRPC.User user : messages.users) {
                            state.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(user.getObjectSize());
                            user.serializeToStream(data);
                            state.bindInteger(1, user.id);
                            if (user.first_name != null && user.last_name != null) {
                                String name = (user.first_name + " " + user.last_name).toLowerCase();
                                state.bindString(2, name);
                            } else {
                                state.bindString(2, "");
                            }
                            if (user.status != null) {
                                state.bindInteger(3, user.status.expires);
                            } else {
                                state.bindInteger(3, 0);
                            }
                            state.bindByteBuffer(4, data.buffer);
                            state.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                    }
                    if (!messages.chats.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chats VALUES(?, ?, ?)");
                        for (TLRPC.Chat chat : messages.chats) {
                            state.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(chat.getObjectSize());
                            chat.serializeToStream(data);
                            state.bindInteger(1, chat.id);
                            if (chat.title != null) {
                                String name = chat.title.toLowerCase();
                                state.bindString(2, name);
                            } else {
                                state.bindString(2, "");
                            }
                            state.bindByteBuffer(3, data.buffer);
                            state.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                    }

                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getDialogs(final int offset, final int serverOffset, final int count) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                TLRPC.messages_Dialogs dialogs = new TLRPC.messages_Dialogs();
                ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<TLRPC.EncryptedChat>();
                try {
                    ArrayList<Integer> usersToLoad = new ArrayList<Integer>();
                    usersToLoad.add(UserConfig.getClientUserId());
                    ArrayList<Integer> chatsToLoad = new ArrayList<Integer>();
                    ArrayList<Integer> encryptedToLoad = new ArrayList<Integer>();
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT d.did, d.last_mid, d.unread_count, d.date, m.data, m.read_state, m.mid, m.send_state FROM dialogs as d LEFT JOIN messages as m ON d.last_mid = m.mid ORDER BY d.date DESC LIMIT %d,%d", offset, count));
                    while (cursor.next()) {
                        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                        dialog.id = cursor.longValue(0);
                        dialog.top_message = cursor.intValue(1);
                        dialog.unread_count = cursor.intValue(2);
                        dialog.last_message_date = cursor.intValue(3);
                        dialogs.dialogs.add(dialog);

                        ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(4));
                        if (data != null && cursor.byteBufferValue(4, data.buffer) != 0) {
                            TLRPC.Message message = (TLRPC.Message)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                            if (message != null) {
                                message.unread = (cursor.intValue(5) != 1);
                                message.id = cursor.intValue(6);
                                message.send_state = cursor.intValue(7);
                                dialogs.messages.add(message);

                                if (!usersToLoad.contains(message.from_id)) {
                                    usersToLoad.add(message.from_id);
                                }
                                if (message.action != null && message.action.user_id != 0) {
                                    if (!usersToLoad.contains(message.action.user_id)) {
                                        usersToLoad.add(message.action.user_id);
                                    }
                                }
                                if (message.fwd_from_id != 0) {
                                    if (!usersToLoad.contains(message.fwd_from_id)) {
                                        usersToLoad.add(message.fwd_from_id);
                                    }
                                }
                            }
                        }
                        buffersStorage.reuseFreeBuffer(data);

                        int lower_id = (int)dialog.id;
                        int high_id = (int)(dialog.id >> 32);
                        if (lower_id != 0) {
                            if (high_id == 1) {
                                if (!chatsToLoad.contains(lower_id)) {
                                    chatsToLoad.add(lower_id);
                                }
                            } else {
                                if (lower_id > 0) {
                                    if (!usersToLoad.contains(lower_id)) {
                                        usersToLoad.add(lower_id);
                                    }
                                } else {
                                    if (!chatsToLoad.contains(-lower_id)) {
                                        chatsToLoad.add(-lower_id);
                                    }
                                }
                            }
                        } else {
                            if (!encryptedToLoad.contains(high_id)) {
                                encryptedToLoad.add(high_id);
                            }
                        }
                    }
                    cursor.dispose();

                    if (!encryptedToLoad.isEmpty()) {
                        String toLoad = "";
                        for (int uid : encryptedToLoad) {
                            if (toLoad.length() != 0) {
                                toLoad += ",";
                            }
                            toLoad += uid;
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid IN(%s)", toLoad));
                        while (cursor.next()) {
                            try {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    if (chat != null) {
                                        encryptedChats.add(chat);
                                        chat.user_id = cursor.intValue(1);
                                        if (!usersToLoad.contains(chat.user_id)) {
                                            usersToLoad.add(chat.user_id);
                                        }
                                        chat.a_or_b = cursor.byteArrayValue(2);
                                        chat.auth_key = cursor.byteArrayValue(3);
                                        chat.ttl = cursor.intValue(4);
                                    }
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                        cursor.dispose();
                    }

                    if (!chatsToLoad.isEmpty()) {
                        String toLoad = "";
                        for (int uid : chatsToLoad) {
                            if (toLoad.length() != 0) {
                                toLoad += ",";
                            }
                            toLoad += uid;
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid IN(%s)", toLoad));
                        while (cursor.next()) {
                            try {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.Chat chat = (TLRPC.Chat)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    if (chat != null) {
                                        dialogs.chats.add(chat);
                                    }
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                        cursor.dispose();
                    }

                    if (!usersToLoad.isEmpty()) {
                        String toLoad = "";
                        for (int uid : usersToLoad) {
                            if (toLoad.length() != 0) {
                                toLoad += ",";
                            }
                            toLoad += uid;
                        }
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", toLoad));
                        while (cursor.next()) {
                            try {
                                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                                    TLRPC.User user = (TLRPC.User)TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                                    if (user != null) {
                                        if (user.status != null) {
                                            user.status.expires = cursor.intValue(1);
                                        }
                                        dialogs.users.add(user);
                                    }
                                }
                                buffersStorage.reuseFreeBuffer(data);
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                        cursor.dispose();
                    }
                    MessagesController.getInstance().processLoadedDialogs(dialogs, encryptedChats, offset, serverOffset, count, true, false);
                } catch (Exception e) {
                    dialogs.dialogs.clear();
                    dialogs.users.clear();
                    dialogs.chats.clear();
                    encryptedChats.clear();
                    FileLog.e("tmessages", e);
                    /*try {
                        database.executeFast("DELETE FROM dialogs WHERE 1").stepThis().dispose();
                    } catch (Exception e2) {
                        FileLog.e("tmessages", e);
                    }*/
                    MessagesController.getInstance().processLoadedDialogs(dialogs, encryptedChats, 0, 0, 100, true, true);
                }
            }
        });
    }

    public void putDialogs(final TLRPC.messages_Dialogs dialogs) {
        if (dialogs.dialogs.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    final HashMap<Integer, TLRPC.Message> new_dialogMessage = new HashMap<Integer, TLRPC.Message>();
                    for (TLRPC.Message message : dialogs.messages) {
                        new_dialogMessage.put(message.id, message);
                    }

                    if (!dialogs.dialogs.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
                        SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO dialogs(did, date, unread_count, last_mid) VALUES(?, ?, ?, ?)");
                        SQLitePreparedStatement state3 = database.executeFast("REPLACE INTO media VALUES(?, ?, ?, ?)");
                        SQLitePreparedStatement state4 = database.executeFast("REPLACE INTO dialog_settings VALUES(?, ?)");

                        for (TLRPC.TL_dialog dialog : dialogs.dialogs) {
                            state.requery();
                            state2.requery();
                            state4.requery();
                            int uid = dialog.peer.user_id;
                            if (uid == 0) {
                                uid = -dialog.peer.chat_id;
                            }
                            TLRPC.Message message = new_dialogMessage.get(dialog.top_message);
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(message.getObjectSize());
                            message.serializeToStream(data);

                            state.bindInteger(1, message.id);
                            state.bindInteger(2, uid);
                            state.bindInteger(3, (message.unread ? 0 : 1));
                            state.bindInteger(4, message.send_state);
                            state.bindInteger(5, message.date);
                            state.bindByteBuffer(6, data.buffer);
                            state.bindInteger(7, (message.out ? 1 : 0));
                            state.bindInteger(8, 0);
                            state.step();

                            state2.bindLong(1, uid);
                            state2.bindInteger(2, message.date);
                            state2.bindInteger(3, dialog.unread_count);
                            state2.bindInteger(4, dialog.top_message);
                            state2.step();

                            state4.bindLong(1, uid);
                            state4.bindInteger(2, dialog.notify_settings.mute_until != 0 ? 1 : 0);
                            state4.step();

                            if (message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaPhoto) {
                                state3.requery();
                                state3.bindLong(1, message.id);
                                state3.bindInteger(2, uid);
                                state3.bindInteger(3, message.date);
                                state3.bindByteBuffer(4, data.buffer);
                                state3.step();
                            }
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                        state2.dispose();
                        state3.dispose();
                        state4.dispose();
                    }

                    if (!dialogs.users.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO users VALUES(?, ?, ?, ?)");
                        for (TLRPC.User user : dialogs.users) {
                            state.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(user.getObjectSize());
                            user.serializeToStream(data);
                            state.bindInteger(1, user.id);
                            if (user.first_name != null && user.last_name != null) {
                                String name = (user.first_name + " " + user.last_name).toLowerCase();
                                state.bindString(2, name);
                            } else {
                                state.bindString(2, "");
                            }
                            if (user.status != null) {
                                state.bindInteger(3, user.status.expires);
                            } else {
                                state.bindInteger(3, 0);
                            }
                            state.bindByteBuffer(4, data.buffer);
                            state.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                    }

                    if (!dialogs.chats.isEmpty()) {
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chats VALUES(?, ?, ?)");
                        for (TLRPC.Chat chat : dialogs.chats) {
                            state.requery();
                            ByteBufferDesc data = buffersStorage.getFreeBuffer(chat.getObjectSize());
                            chat.serializeToStream(data);
                            state.bindInteger(1, chat.id);
                            if (chat.title != null) {
                                String name = chat.title.toLowerCase();
                                state.bindString(2, name);
                            } else {
                                state.bindString(2, "");
                            }
                            state.bindByteBuffer(3, data.buffer);
                            state.step();
                            buffersStorage.reuseFreeBuffer(data);
                        }
                        state.dispose();
                    }

                    database.commitTransaction();

                    loadUnreadMessages();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public TLRPC.User getUser(final int user_id) {
        TLRPC.User user = null;
        try {
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid = %d", user_id));
            if (cursor.next()) {
                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                    user = (TLRPC.User) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                    if (user != null) {
                        if (user.status != null) {
                            user.status.expires = cursor.intValue(1);
                        }
                    }
                }
                buffersStorage.reuseFreeBuffer(data);
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return user;
    }

    public ArrayList<TLRPC.User> getUsers(final ArrayList<Integer> uids, final boolean[] error) {
        ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
        try {
            String uidsStr = "";

            for (Integer uid : uids) {
                if (uidsStr.length() != 0) {
                    uidsStr += ",";
                }
                uidsStr += uid;
            }

            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN (%s)", uidsStr));
            while (cursor.next()) {
                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                    TLRPC.User user = (TLRPC.User) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                    if (user != null) {
                        if (user.status != null) {
                            user.status.expires = cursor.intValue(1);
                        }
                        users.add(user);
                    } else {
                        error[0] = true;
                        buffersStorage.reuseFreeBuffer(data);
                        break;
                    }
                } else {
                    error[0] = true;
                    buffersStorage.reuseFreeBuffer(data);
                    break;
                }
                buffersStorage.reuseFreeBuffer(data);
            }
            cursor.dispose();
        } catch (Exception e) {
            error[0] = true;
            FileLog.e("tmessages", e);
        }
        return users;
    }

    public TLRPC.Chat getChat(final int chat_id) {
        TLRPC.Chat chat = null;
        try {
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid = %d", chat_id));
            if (cursor.next()) {
                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                    chat = (TLRPC.Chat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                }
                buffersStorage.reuseFreeBuffer(data);
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return chat;
    }

    public TLRPC.EncryptedChat getEncryptedChat(final int chat_id) {
        TLRPC.EncryptedChat chat = null;
        try {
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl FROM enc_chats WHERE uid = %d", chat_id));
            if (cursor.next()) {
                ByteBufferDesc data = buffersStorage.getFreeBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data.buffer) != 0) {
                    chat = (TLRPC.EncryptedChat) TLClassStore.Instance().TLdeserialize(data, data.readInt32());
                    if (chat != null) {
                        chat.user_id = cursor.intValue(1);
                        chat.a_or_b = cursor.byteArrayValue(2);
                        chat.auth_key = cursor.byteArrayValue(3);
                        chat.ttl = cursor.intValue(4);
                    }
                }
                buffersStorage.reuseFreeBuffer(data);
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return chat;
    }
}

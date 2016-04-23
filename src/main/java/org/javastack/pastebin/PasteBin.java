/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.javastack.pastebin;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.javastack.kvstore.KVStoreFactory;
import org.javastack.kvstore.holders.DataHolder;
import org.javastack.kvstore.io.FileStreamStore;
import org.javastack.kvstore.io.StringSerializer;
import org.javastack.kvstore.structures.btree.BplusTree.InvalidDataException;
import org.javastack.kvstore.structures.btree.BplusTreeFile;
import org.javastack.packer.Base64;

/**
 * Lightweight clone of concept behind pastebin.com
 * 
 * @author Guillermo Grandes / guillermo.grandes[at]gmail.com
 */
public class PasteBin extends HttpServlet {
	private static final Logger log = Logger.getLogger(PasteBin.class);
	private static final long serialVersionUID = 1L;
	//
	private static final int MAX_LENGTH = 65520;
	private static final int KEY_SPACE = 8;
	private static final int MAX_COLLISION = 5;
	//
	private static final Charset iso = Charset.forName("ISO-8859-1");
	private PersistentStorage store;
	private MessageDigest md;

	public PasteBin() {
	}

	@Override
	public void init() throws ServletException {
		final String storeDir = getServletContext().getRealPath("/WEB-INF/storage/");
		log.info("ServletContext StoragePath: " + storeDir);
		try {
			md = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new ServletException("Unable to instantiate MessageDigest", e);
		}
		try {
			store = new PersistentStorage(storeDir);
			store.open();
		} catch (Exception e) {
			throw new ServletException("Unable to instantiate PersistentStorage", e);
		}
	}

	@Override
	public void destroy() {
		store.close();
	}

	@Override
	protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		final PrintWriter out = response.getWriter();
		final String key = getPathInfoKey(request.getPathInfo());
		if (key != null) {
			final MetaHolder meta = store.get(key);
			if (meta != null) {
				// Found - send response
				response.setContentType("text/plain; charset=ISO-8859-1");
				out.println(meta.data);
				return;
			}
		}
		sendError(response, out, HttpServletResponse.SC_NOT_FOUND, "Not Found");
	}

	@Override
	protected void doPost(final HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		final PrintWriter out = response.getWriter();
		String data = request.getParameter("data");
		if ((data == null) || ((data = data.trim()).length() < 4)) {
			sendError(response, out, HttpServletResponse.SC_BAD_REQUEST, "Invalid Data Parameter");
			return;
		}
		if (data.length() > MAX_LENGTH) {
			sendError(response, out, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
					"Request Entity Too Large");
			return;
		}
		String key = hashData(data);
		int collision = 0;
		while (true) { // Handle possible collisions
			final MetaHolder meta = store.get(key);
			// Dont exists
			if (meta == null)
				break;
			// Duplicated
			if (data.equals(meta.data)) {
				sendResponse(response, out, key);
				return;
			}
			// Collision
			if (++collision > MAX_COLLISION) {
				log.error("Too many collisions { id=" + key + " }");
				sendError(response, out, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"ERROR: Unable to Store Data");
				return;
			}
			key = hashData(Integer.toString(collision) + ":" + data);
		}
		store.put(key, data);
		sendResponse(response, out, key);
	}

	private static final void sendResponse(final HttpServletResponse response, final PrintWriter out,
			final String key) {
		final String res = "{ \"id\": \"" + key + "\" }";
		// Send Response
		response.setContentType("application/json");
		out.println(res);
	}

	private static final void sendError(final HttpServletResponse response, final PrintWriter out,
			final int status, final String msg) {
		response.setContentType("text/plain; charset=ISO-8859-1");
		response.setStatus(status);
		out.println(msg);
	}

	private static final String getPathInfoKey(final String pathInfo) {
		if (pathInfo == null)
			return null;
		if (pathInfo.isEmpty())
			return null;
		return pathInfo.substring(1);
	}

	private final String hashData(final String data) {
		byte[] b = data.getBytes(iso);
		synchronized (md) {
			b = md.digest(b);
			md.reset();
		}
		return new String(Base64.encode(b, true), iso).substring(0, KEY_SPACE);
	}

	static class PersistentStorage {
		private static final int BUF_LEN = 0x10000;
		private final KVStoreFactory<TokenHolder, MetaHolder> fac = new KVStoreFactory<TokenHolder, MetaHolder>(
				TokenHolder.class, MetaHolder.class);
		private final BplusTreeFile<TokenHolder, MetaHolder> map;
		private final FileStreamStore stream;
		private final ByteBuffer wbuf, rbuf;

		public PersistentStorage(final String storeDirName) throws InstantiationException,
				IllegalAccessException, IOException {
			final File storeDir = new File(storeDirName);
			if (!storeDir.exists()) {
				if (!storeDir.mkdirs())
					throw new IOException("Invalid storeDir: " + storeDirName);
			}
			final File storeTree = new File(storeDir, "tree");
			final File storeStream = new File(storeDir, "stream");
			wbuf = ByteBuffer.allocate(BUF_LEN);
			rbuf = ByteBuffer.allocate(BUF_LEN);
			map = fac.createTreeFile(fac.createTreeOptionsDefault()
					.set(KVStoreFactory.FILENAME, storeTree.getCanonicalPath())
					.set(KVStoreFactory.DISABLE_POPULATE_CACHE, true));
			stream = new FileStreamStore(storeStream, BUF_LEN);
			stream.setAlignBlocks(true);
			stream.setFlushOnWrite(true);
		}

		public void open() throws InvalidDataException {
			try {
				if (map.open())
					log.info("open tree ok");
			} catch (InvalidDataException e) {
				log.error("open tree error, recovery needed");
				if (map.recovery(false) && map.open()) {
					log.info("recovery ok, tree opened");
				} else {
					throw e;
				}
			}
			stream.open();
		}

		public void close() {
			stream.close();
			map.close();
		}

		public void put(final String k, final String v) {
			long offset = -1;
			synchronized (wbuf) {
				wbuf.clear();
				StringSerializer.fromStringToBuffer(wbuf, v);
				wbuf.flip();
				offset = stream.write(wbuf);
			}
			map.put(TokenHolder.valueOf(k), MetaHolder.valueOf(offset));
		}

		public void put(final String k, final MetaHolder meta) {
			map.put(TokenHolder.valueOf(k), meta);
		}

		public MetaHolder get(final String k) {
			final MetaHolder meta = map.get(TokenHolder.valueOf(k));
			if (meta == null)
				return null;
			synchronized (rbuf) {
				rbuf.clear();
				stream.read(meta.offset, rbuf);
				meta.data = StringSerializer.fromBufferToString(rbuf);
			}
			log.info("Found meta=" + meta);
			return meta;
		}

		public void remove(final String k) {
			map.remove(TokenHolder.valueOf(k));
		}
	}

	public static class TokenHolder extends DataHolder<TokenHolder> {
		private final String token;

		public TokenHolder() {
			this("");
		}

		public TokenHolder(final String token) {
			this.token = token;
		}

		public static TokenHolder valueOf(final String token) {
			return new TokenHolder(token);
		}

		@Override
		public int compareTo(final TokenHolder other) {
			return token.compareTo(other.token);
		}

		@Override
		public boolean equals(final Object other) {
			if (!(other instanceof TokenHolder))
				return false;
			return token.equals(((TokenHolder) other).token);
		}

		@Override
		public int hashCode() {
			return token.hashCode();
		}

		@Override
		public String toString() {
			return token;
		}

		@Override
		public int byteLength() {
			return 4 + KEY_SPACE + 1;
		}

		@Override
		public void serialize(final ByteBuffer bb) {
			StringSerializer.fromStringToBuffer(bb, token);
		}

		@Override
		public TokenHolder deserialize(final ByteBuffer bb) {
			return TokenHolder.valueOf(StringSerializer.fromBufferToString(bb));
		}
	}

	public static class MetaHolder extends DataHolder<MetaHolder> {
		public final long offset;
		public int timestamp;
		// Stored in Secondary Stream (pointed by offset)
		public String data = null;

		public MetaHolder() {
			this(0, 0);
		}

		public MetaHolder(final long offset, final int timestamp) {
			this.offset = offset;
			this.timestamp = timestamp;
		}

		public static MetaHolder valueOf(final long offset) {
			return new MetaHolder(offset, (int) (System.currentTimeMillis() / 1000));
		}

		@Override
		public int compareTo(final MetaHolder o) {
			if (offset < o.offset)
				return -1;
			if (offset > o.offset)
				return 1;
			if (timestamp < o.timestamp)
				return -1;
			if (timestamp > o.timestamp)
				return 1;
			return 0;
		}

		@Override
		public boolean equals(final Object other) {
			if (!(other instanceof MetaHolder))
				return false;
			return (compareTo((MetaHolder) other) == 0);
		}

		@Override
		public int hashCode() {
			return (int) (offset ^ (offset >>> 32));
		}

		@Override
		public String toString() {
			return "offset=" + offset + " timestamp=" + timestamp;
		}

		@Override
		public int byteLength() {
			return 8 + 4;
		}

		@Override
		public void serialize(final ByteBuffer bb) {
			bb.putLong(offset);
			bb.putInt(timestamp);
		}

		@Override
		public MetaHolder deserialize(final ByteBuffer bb) {
			return new MetaHolder(bb.getLong(), bb.getInt());
		}
	}
}

/*
 * ConnectionManager.java 
 * Created on 2011-08-24
 *
 * Copyright (c) Verax Systems 2011.
 * All rights reserved.
 *
 * This software is furnished under a license. Use, duplication,
 * disclosure and all other uses are restricted to the rights
 * specified in the written license agreement.
 */
package com.veraxsystems.vxipmi.connection;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import com.veraxsystems.vxipmi.coding.commands.PrivilegeLevel;
import com.veraxsystems.vxipmi.coding.commands.session.GetChannelAuthenticationCapabilitiesResponseData;
import com.veraxsystems.vxipmi.coding.security.CipherSuite;
import com.veraxsystems.vxipmi.common.PropertiesManager;
import com.veraxsystems.vxipmi.transport.Messenger;
import com.veraxsystems.vxipmi.transport.UdpListener;
import com.veraxsystems.vxipmi.transport.UdpMessenger;

/**
 * Manages multiple {@link Connection}s
 */
public class ConnectionManager {

	private final Messenger messenger;
	private final List<Connection> connections;
	private final ScheduledExecutorService timer;

	private static Integer sessionId = 100;
	private static Integer sessionlessTag = 0;
	private static final ReentrantLock sessionIdGeneratorLock = new ReentrantLock(true);
	private static final List<Integer> reservedTags = new ArrayList<Integer>();
	private static final int DEFAULT_TIMER_POOL_SIZE = 5;

	/**
	 * Frequency of the no-op commands that will be sent to keep up the session
	 */
	private static int pingPeriod = -1;

	/**
	 * Initiates the connection manager. Wildcard IP address will be used.
	 * 
	 * @param port
	 *            - the port at which {@link UdpListener} will work
	 * @throws IOException
	 *             when properties file was not found
	 */
	public ConnectionManager(int port) throws IOException {
		this(new UdpMessenger(port));
	}

	/**
	 * Initiates the connection manager.
	 * 
	 * @param port
	 *            - the port at which {@link UdpListener} will work
	 * @param address
	 *            - the IP interface {@link UdpListener} will bind to
	 * @throws IOException
	 *             when properties file was not found
	 */
	public ConnectionManager(int port, InetAddress address) throws IOException {
		this(new UdpMessenger(port, address));
	}

	/**
	 * Initiates the connection manager.
	 * 
	 * @param messenger
	 *            - {@link Messenger} to be used in communication
	 * @throws IOException
	 *             when properties file was not found
	 */
	public ConnectionManager(Messenger messenger) throws IOException {
		this.messenger = messenger;
		connections = new ArrayList<Connection>();
		final PropertiesManager propertiesManager = PropertiesManager.getInstance();

		if (pingPeriod == -1) {
            pingPeriod = Integer.parseInt(propertiesManager.getProperty("pingPeriod"));
        }

		final int poolSize;
		final String property = propertiesManager.getProperty("timerThreadPoolSize");

		if (property == null) {
			poolSize = DEFAULT_TIMER_POOL_SIZE;
		} else {
			poolSize = Integer.parseInt(property);
		}
		timer = Executors.newScheduledThreadPool(poolSize, new TimerThreadFactory());
	}

	/**
	 * Closes all open connections and disconnects {@link UdpListener}.
	 */
	public void close() {
		synchronized (connections) {
			for (Connection connection : connections) {
				if (connection != null && connection.isActive()) {
					connection.disconnect();
				}
			}
		}
		messenger.closeConnection();
	}

	/**
	 * The session ID generated by the {@link ConnectionManager}.
	 * Auto-incremented.
	 */
	static int generateSessionId() {
		try {
			sessionIdGeneratorLock.lock();
			sessionId %= (Integer.MAX_VALUE / 4);
			return sessionId++;
		} finally {
			sessionIdGeneratorLock.unlock();
		}
	}

	/**
	 * The tag for messages sent outside the session generated by the
	 * {@link ConnectionManager}. Auto-incremented.
	 */
	public static int generateSessionlessTag() {
		synchronized (reservedTags) {
			boolean wait = true;
			while (wait) {
				++sessionlessTag;
				sessionlessTag %= 60;
				if (reservedTags.contains(sessionlessTag)) {
					try {
						reservedTags.wait();
					} catch (InterruptedException e) {
						// TODO log
					}
				} else {
					reservedTags.add(sessionlessTag);
					wait = false;
				}
			}
			return sessionlessTag;
		}
	}

	/**
	 * Frees the sessionless tag for further use
	 * 
	 * @param tag
	 *            - tag to free
	 */
	private static void freeTag(int tag) {
		synchronized (reservedTags) {
			reservedTags.remove((Integer) tag);
			reservedTags.notifyAll();
		}
	}

	/**
	 * Returns {@link Connection} identified by index.
	 * 
	 * @param index
	 *            - index of the connection to return
	 */
	public Connection getConnection(int index) {
		return connections.get(index);
	}

	/**
	 * Closes the connection with the given index.
	 */
	public void closeConnection(int index) {
		connections.get(index).disconnect();
	}

	/**
	 * Creates and initiates {@link Connection} to the remote host.
	 * 
	 * @param address
	 *            - {@link InetAddress} of the remote host
	 * @param pingPeriod
	 *            - frequency of the no-op commands that will be sent to keep up
	 *            the session
	 * @return index of the connection
	 * @throws IOException
	 *             - when properties file was not found
	 * @throws FileNotFoundException
	 *             - when properties file was not found
	 */
	public int createConnection(InetAddress address, int pingPeriod) throws IOException {
		Connection connection = new Connection(timer, messenger, 0);
		connection.connect(address, pingPeriod);

		synchronized (connections) {
			connections.add(connection);
			return connections.size() - 1;
		}
	}

	/**
	 * Creates and initiates {@link Connection} to the remote host with the
	 * default ping frequency.
	 * 
	 * @param address
	 *            - {@link InetAddress} of the remote host
	 * @return index of the connection
	 * @throws IOException
	 *             when properties file was not found
	 * @throws FileNotFoundException
	 *             when properties file was not found
	 */
	public int createConnection(InetAddress address) throws IOException {

		synchronized (connections) {
			Connection connection = new Connection(timer, messenger, connections.size());
			connection.connect(address, pingPeriod);
			connections.add(connection);
			return connections.size() - 1;
		}
	}

	/**
	 * Gets from the managed system supported {@link CipherSuite}s. Should be
	 * performed only immediately after {@link #createConnection}.
	 * 
	 * @param connection
	 *            - index of the connection to get available Cipher Suites from
	 * 
	 * @return list of the {@link CipherSuite}s supported by the managed system.
	 * @throws ConnectionException
	 *             when connection is in the state that does not allow to
	 *             perform this operation.
	 * @throws Exception
	 *             when sending message to the managed system fails
	 */
	public List<CipherSuite> getAvailableCipherSuites(int connection)
			throws Exception {
		int tag = generateSessionlessTag();
		List<CipherSuite> suites;
		try {
			suites = connections.get(connection).getAvailableCipherSuites(tag);
		} catch (Exception e) {
			freeTag(tag);
			throw e;
		}
		freeTag(tag);
		return suites;
	}

	/**
	 * Queries the managed system for the details of the authentification
	 * process. Must be performed after {@link #getAvailableCipherSuites(int)}
	 * 
	 * @param connection
	 *            - index of the connection to get Channel Authentication
	 *            Capabilities from
	 * @param cipherSuite
	 *            - {@link CipherSuite} requested for the session
	 * @param requestedPrivilegeLevel
	 *            - {@link PrivilegeLevel} requested for the session
	 * @return {@link GetChannelAuthenticationCapabilitiesResponseData}
	 * @throws ConnectionException
	 *             when connection is in the state that does not allow to
	 *             perform this operation.
	 * @throws Exception
	 *             when sending message to the managed system fails
	 */
	public GetChannelAuthenticationCapabilitiesResponseData getChannelAuthenticationCapabilities(
			int connection, CipherSuite cipherSuite,
			PrivilegeLevel requestedPrivilegeLevel) throws Exception {
		int tag = generateSessionlessTag();
		GetChannelAuthenticationCapabilitiesResponseData responseData;
		try {
			responseData = connections.get(connection)
					.getChannelAuthenticationCapabilities(tag, cipherSuite,
							requestedPrivilegeLevel);
		} catch (Exception e) {
			freeTag(tag);
			throw e;
		}
		freeTag(tag);
		return responseData;
	}

	/**
	 * Initiates the session with the managed system. Must be performed after
	 * {@link #getChannelAuthenticationCapabilities(int, CipherSuite, PrivilegeLevel)}
	 * 
	 * @param connection
	 *            - index of the connection that starts the session
	 * @param cipherSuite
	 *            - {@link CipherSuite} that will be used during the session
	 * @param privilegeLevel
	 *            - requested {@link PrivilegeLevel} - most of the time it will
	 *            be {@link PrivilegeLevel#User}
	 * @param username
	 *            - the username
	 * @param password
	 *            - the password matching the username
	 * @param bmcKey
	 *            - the key that should be provided if the two-key
	 *            authentication is enabled, null otherwise.
	 * @throws ConnectionException
	 *             when connection is in the state that does not allow to
	 *             perform this operation.
	 * @throws Exception
	 *             when sending message to the managed system or initializing
	 *             one of the cipherSuite's algorithms fails
	 */
	public void startSession(int connection, CipherSuite cipherSuite,
			PrivilegeLevel privilegeLevel, String username, String password,
			byte[] bmcKey) throws Exception {
		int tag = generateSessionlessTag();
		try {
			connections.get(connection).startSession(tag, cipherSuite,
					privilegeLevel, username, password, bmcKey);
		} catch (Exception e) {
			freeTag(tag);
			throw e;
		}
		freeTag(tag);
	}

	/**
	 * Registers the listener so it will receive notifications from connection
	 * 
	 * @param connection
	 *            - index of the {@link Connection} to listen to
	 * @param listener
	 *            - {@link ConnectionListener} to notify
	 */
	public void registerListener(int connection, ConnectionListener listener) {
		connections.get(connection).registerListener(listener);
	}
}

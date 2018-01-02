/*******************************************************************************
 *
 *    Copyright (C) 2015-2018 Jan Kristof Nidzwetzki
 *  
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License. 
 *    
 *******************************************************************************/
package com.github.jnidzwetzki.bitfinex.v2;

import java.io.Closeable;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jnidzwetzki.bitfinex.v2.callback.api.APICallbackHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.DoNothingHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.HeartbeatHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.NotificationHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.OrderHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.PositionHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.api.WalletHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.channel.CandlestickHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.channel.ChannelCallbackHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.channel.TickHandler;
import com.github.jnidzwetzki.bitfinex.v2.callback.channel.TradeOrderbookHandler;
import com.github.jnidzwetzki.bitfinex.v2.commands.AbstractAPICommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.AuthCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.CancelOrderCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.CancelOrderGroupCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.CommandException;
import com.github.jnidzwetzki.bitfinex.v2.commands.OrderCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.SubscribeCandlesCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.SubscribeTickerCommand;
import com.github.jnidzwetzki.bitfinex.v2.commands.SubscribeTradingOrderbookCommand;
import com.github.jnidzwetzki.bitfinex.v2.entity.APIException;
import com.github.jnidzwetzki.bitfinex.v2.entity.BitfinexOrder;
import com.github.jnidzwetzki.bitfinex.v2.entity.TradeOrderbookConfiguration;
import com.github.jnidzwetzki.bitfinex.v2.entity.Wallet;
import com.github.jnidzwetzki.bitfinex.v2.entity.symbol.BitfinexCandlestickSymbol;
import com.github.jnidzwetzki.bitfinex.v2.entity.symbol.BitfinexCurrencyPair;
import com.github.jnidzwetzki.bitfinex.v2.entity.symbol.BitfinexStreamSymbol;
import com.github.jnidzwetzki.bitfinex.v2.manager.OrderManager;
import com.github.jnidzwetzki.bitfinex.v2.manager.PositionManager;
import com.github.jnidzwetzki.bitfinex.v2.manager.QuoteManager;
import com.github.jnidzwetzki.bitfinex.v2.manager.TradingOrderbookManager;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

public class BitfinexApiBroker implements Closeable {

	/**
	 * The bitfinex api
	 */
	public final static String BITFINEX_URI = "wss://api.bitfinex.com/ws/2";
	
	/**
	 * The API callback
	 */
	private final Consumer<String> apiCallback = ((c) -> websocketCallback(c));
	
	/**
	 * The websocket endpoint
	 */
	private WebsocketClientEndpoint websocketEndpoint;
	
	/**
	 * The channel map
	 */
	private final Map<Integer, BitfinexStreamSymbol> channelIdSymbolMap;
	
	/**
	 * The tick manager
	 */
	private final QuoteManager quoteManager;
	
	/**
	 * The trading orderbook manager
	 */
	private final TradingOrderbookManager tradingOrderbookManager;
	
	/**
	 * The position manager
	 */
	private final PositionManager positionManager;
	
	/**
	 * The order manager
	 */
	private final OrderManager orderManager;
	
	/**
	 * The last heartbeat value
	 */
	protected final AtomicLong lastHeatbeat;

	/**
	 * The heartbeat thread
	 */
	private Thread heartbeatThread;
	
	/**
	 * The API key
	 */
	private String apiKey;
	
	/**
	 * The API secret
	 */
	private String apiSecret;
	
	/**
	 * The connection ready latch
	 */
	private CountDownLatch connectionReadyLatch;
	
	/**
	 * Event on the latch until the connection is ready
	 * - Authenticated
	 * - Order snapshots read
	 * - Wallet snapshot read
	 * - Position snapshot read
	 */
	private final static int CONNECTION_READY_EVENTS = 4;
	
	/**
	 * Is the connection authenticated?
	 */
	private boolean authenticated;
	
	/**
	 * Wallets
	 * 
	 *  Currency, Wallet-Type, Wallet
	 */
	private final Table<String, String, Wallet> walletTable;
	
	/**
	 * The channel handler
	 */
	private final Map<String, APICallbackHandler> channelHandler;
	
	/**
	 * The executor service
	 */
	private final ExecutorService executorService;
	
	/**
	 * The Logger
	 */
	private final static Logger logger = LoggerFactory.getLogger(BitfinexApiBroker.class);

	public BitfinexApiBroker() {
		this.executorService = Executors.newFixedThreadPool(10);
		this.channelIdSymbolMap = new HashMap<>();
		this.lastHeatbeat = new AtomicLong();
		this.quoteManager = new QuoteManager(this);
		this.tradingOrderbookManager = new TradingOrderbookManager(this);
		this.orderManager = new OrderManager(this);
		this.positionManager = new PositionManager(executorService);
		this.walletTable = HashBasedTable.create();
		this.authenticated = false;
		this.channelHandler = new HashMap<>();

		setupChannelHandler();
	}

	/**
	 * Setup the channel handler
	 */
	private void setupChannelHandler() {
		// Heartbeat
		channelHandler.put("hb", new HeartbeatHandler());
		// Position snapshot
		channelHandler.put("ps", new PositionHandler());
		// Position new
		channelHandler.put("pn", new PositionHandler());
		// Position updated
		channelHandler.put("pu", new PositionHandler());
		// Position caneled
		channelHandler.put("pc", new PositionHandler());
		// Founding offers
		channelHandler.put("fos", new DoNothingHandler());
		// Founding credits
		channelHandler.put("fcs", new DoNothingHandler());
		// Founding loans
		channelHandler.put("fls", new DoNothingHandler());
		// Ats - Unkown
		channelHandler.put("ats", new DoNothingHandler());
		// Wallet snapshot
		channelHandler.put("ws", new WalletHandler());
		// Wallet update
		channelHandler.put("wu", new WalletHandler());
		// Order snapshot
		channelHandler.put("os", new OrderHandler());
		// Order notification
		channelHandler.put("on", new OrderHandler());
		// Order update
		channelHandler.put("ou", new OrderHandler());
		// Order cancelation
		channelHandler.put("oc", new OrderHandler());
		// Trade executed
		channelHandler.put("te", new DoNothingHandler());
		// Trade update
		channelHandler.put("tu", new DoNothingHandler());
		// General notification 
		channelHandler.put("n", new NotificationHandler());
	}
	
	public BitfinexApiBroker(final String apiKey, final String apiSecret) {
		this();
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
	}
	
	public void connect() throws APIException {
		try {
			final URI bitfinexURI = new URI(BITFINEX_URI);
			websocketEndpoint = new WebsocketClientEndpoint(bitfinexURI);
			websocketEndpoint.addConsumer(apiCallback);
			websocketEndpoint.connect();
			updateConnectionHeartbeat();
			
			executeAuthentification();
			
			heartbeatThread = new Thread(new HeartbeatThread(this));
			heartbeatThread.start();
		} catch (Exception e) {
			throw new APIException(e);
		}
	}

	/**
	 * Execute the authentification and wait until the socket is ready
	 * @throws InterruptedException
	 * @throws APIException 
	 */
	private void executeAuthentification() throws InterruptedException, APIException {
		connectionReadyLatch = new CountDownLatch(CONNECTION_READY_EVENTS);

		if(isAuthenticatedConnection()) {
			sendCommand(new AuthCommand());
			logger.info("Waiting for connection ready events");
			connectionReadyLatch.await(10, TimeUnit.SECONDS);
			
			if(! authenticated) {
				throw new APIException("Unable to perform authentification");
			}
		}
	}

	/**
	 * Is the connection to be authentificated
	 * @return
	 */
	private boolean isAuthenticatedConnection() {
		return apiKey != null && apiSecret != null;
	}
	
	/**
	 * Was after the connect the authentification successfully?
	 * @return
	 */
	public boolean isAuthenticated() {
		return authenticated;
	}
	
	/**
	 * Disconnect the websocket
	 */
	@Override
	public void close() {
		
		if(heartbeatThread != null) {
			heartbeatThread.interrupt();
			heartbeatThread = null;
		}
		
		if(websocketEndpoint != null) {
			websocketEndpoint.removeConsumer(apiCallback);
			websocketEndpoint.close();
			websocketEndpoint = null;
		}
		
		if(executorService != null) {
			executorService.shutdown();
		}
	}

	/**
	 * Send a new API command
	 * @param apiCommand
	 */
	public void sendCommand(final AbstractAPICommand apiCommand) {
		try {
			final String command = apiCommand.getCommand(this);
			logger.debug("Sending to server: {}", command);
			websocketEndpoint.sendMessage(command);
		} catch (CommandException e) {
			logger.error("Got Exception while sending command", e);
		}
	}
	
	/**
	 * Get the websocket endpoint
	 * @return
	 */
	public WebsocketClientEndpoint getWebsocketEndpoint() {
		return websocketEndpoint;
	}
	
	/**
	 * We received a websocket callback
	 * @param message
	 */
	private void websocketCallback(final String message) {
		logger.debug("Got message: {}", message);
		
		if(message.startsWith("{")) {
			handleAPICallback(message);
		} else if(message.startsWith("[")) {
			handleChannelCallback(message);
		} else {
			logger.error("Got unknown callback: {}", message);
		}
	}

	/**
	 * Handle a API callback
	 */
	protected void handleAPICallback(final String message) {
		
		logger.debug("Got {}", message);
				
		// JSON callback
		final JSONTokener tokener = new JSONTokener(message);
		final JSONObject jsonObject = new JSONObject(tokener);
		
		final String eventType = jsonObject.getString("event");

		switch (eventType) {
		case "info":
			break;
		case "subscribed":
			handleSubscribedCallback(message, jsonObject);
			break;
		case "pong":
			updateConnectionHeartbeat();
			break;
		case "unsubscribed":
			handleUnsubscribedCallback(jsonObject);
			break;
		case "auth":
			handleAuthCallback(jsonObject);
			break;
		default:
			logger.error("Unknown event: {}", message);
		}
	}

	/**
	 * Handle the authentification callback
	 * @param jsonObject
	 */
	private void handleAuthCallback(final JSONObject jsonObject) {
		
		final String status = jsonObject.getString("status");
		
		logger.info("Authentification callback state {}", status);
		
		if(status.equals("OK")) {
			authenticated = true;
		} else {
			authenticated = false;
			logger.error("Unable to authenticate: {}", jsonObject.toString());
		}
		
		if(connectionReadyLatch != null) {
			connectionReadyLatch.countDown();
		}
	}

	/**
	 * Handle new channel unsubscribed callbacks
	 * @param jsonObject
	 */
	private void handleUnsubscribedCallback(final JSONObject jsonObject) {
		synchronized (channelIdSymbolMap) {
			final int channelId = jsonObject.getInt("chanId");
			final BitfinexStreamSymbol symbol = getFromChannelSymbolMap(channelId);
			logger.info("Channel {} ({}) is unsubscribed", channelId, symbol);
			channelIdSymbolMap.remove(channelId);
			channelIdSymbolMap.notifyAll();
		}
	}

	/**
	 * Update the connection heartbeat
	 */
	public void updateConnectionHeartbeat() {
		lastHeatbeat.set(System.currentTimeMillis());
	}

	private void handleSubscribedCallback(final String message, final JSONObject jsonObject) {
		final String channel = jsonObject.getString("channel");
		final int channelId = jsonObject.getInt("chanId");

		if (channel.equals("ticker")) {
			final String symbol = jsonObject.getString("symbol");
			final BitfinexCurrencyPair currencyPair = BitfinexCurrencyPair.fromSymbolString(symbol);
			logger.info("Registering symbol {} on channel {}", currencyPair, channelId);
			addToChannelSymbolMap(channelId, currencyPair);
		} else if (channel.equals("candles")) {
			final String key = jsonObject.getString("key");
			logger.info("Registering key {} on channel {}", key, channelId);
			final BitfinexCandlestickSymbol symbol = BitfinexCandlestickSymbol.fromBitfinexString(key);
			addToChannelSymbolMap(channelId, symbol);
		} else if("book".equals(channel)) {
			final TradeOrderbookConfiguration configuration 
				= TradeOrderbookConfiguration.fromJSON(jsonObject);
			logger.info("Registering book {} on channel {}", jsonObject, channelId);
			addToChannelSymbolMap(channelId, configuration);
		} else {
			logger.error("Unknown subscribed callback {}", message);
		}
	}

	/**
	 * Add channel to symbol map
	 * @param channelId
	 * @param symbol
	 */
	private void addToChannelSymbolMap(final int channelId, final BitfinexStreamSymbol symbol) {
		synchronized (channelIdSymbolMap) {
			channelIdSymbolMap.put(channelId, symbol);
			channelIdSymbolMap.notifyAll();
		}
	}

	/**
	 * Handle a channel callback
	 * @param message
	 */
	protected void handleChannelCallback(final String message) {
		// Channel callback
		logger.debug("Channel callback");
		updateConnectionHeartbeat();

		// JSON callback
		final JSONTokener tokener = new JSONTokener(message);
		final JSONArray jsonArray = new JSONArray(tokener);
				
		final int channel = jsonArray.getInt(0);
		
		if(channel == 0) {
			handleSignalingChannelData(message, jsonArray);
		} else {
			handleChannelData(jsonArray);
		}
	}

	/**
	 * Handle signaling channel data
	 * @param message
	 * @param jsonArray
	 */
	private void handleSignalingChannelData(final String message, final JSONArray jsonArray) {
		
		if(message.contains("ERROR")) {
			logger.error("Got Error message: {}", message);
		}
		
		final String subchannel = jsonArray.getString(1);

		if(! channelHandler.containsKey(subchannel)) {
			logger.error("No match found for message {}", message);
		} else {
			final APICallbackHandler channelHandlerCallback = channelHandler.get(subchannel);
			
			try {
				channelHandlerCallback.handleChannelData(this, jsonArray);
			} catch (APIException e) {
				logger.error("Got exception while handling callback", e);
			}
		}
	}

	/**
	 * Handle normal channel data
	 * @param jsonArray
	 * @param channel
	 * @throws APIException 
	 */
	private void handleChannelData(final JSONArray jsonArray) {
		final int channel = jsonArray.getInt(0);
		final BitfinexStreamSymbol channelSymbol = getFromChannelSymbolMap(channel);

		if(channelSymbol == null) {
			logger.error("Unable to determine symbol for channel {}", channel);
			logger.error("Data is {}", jsonArray);
			return;
		}
		
		if(jsonArray.get(1) instanceof String) {
			final String value = jsonArray.getString(1);
			
			if("hb".equals(value)) {
				quoteManager.updateChannelHeartbeat(channelSymbol);		
			} else {
				logger.error("Unable to process: {}", jsonArray);
			}
		} else {	
			final JSONArray subarray = jsonArray.getJSONArray(1);			

			try {
				if(channelSymbol instanceof BitfinexCandlestickSymbol) {
					final ChannelCallbackHandler handler = new CandlestickHandler();
					handler.handleChannelData(this, channelSymbol, subarray);
				} else if(channelSymbol instanceof TradeOrderbookConfiguration) {
					final TradeOrderbookHandler handler = new TradeOrderbookHandler();
					handler.handleChannelData(this, channelSymbol, subarray);
				} else if(channelSymbol instanceof BitfinexCurrencyPair) {
					final ChannelCallbackHandler handler = new TickHandler();
					handler.handleChannelData(this, channelSymbol, subarray);
				} else {
					logger.error("Unknown stream type: {}", channelSymbol);
				}
			} catch (APIException e) {
				logger.error("Got exception while handling callback", e);
			}
		}
	}

	/**
	 * Get the channel from the symbol map - thread safe
	 * @param channel
	 * @return
	 */
	public BitfinexStreamSymbol getFromChannelSymbolMap(final int channel) {
		synchronized (channelIdSymbolMap) {
			return channelIdSymbolMap.get(channel);
		}
	}
	
	/**
	 * Test whether the ticker is active or not 
	 * @param symbol
	 * @return
	 */
	public boolean isTickerActive(final BitfinexCurrencyPair symbol) {
		return getChannelForSymbol(symbol) != -1;
	}

	/**
	 * Find the channel for the given symbol
	 * @param symbol
	 * @return
	 */
	public int getChannelForSymbol(final BitfinexStreamSymbol symbol) {
		synchronized (channelIdSymbolMap) {
			return channelIdSymbolMap.entrySet()
					.stream()
					.filter((v) -> v.getValue().equals(symbol))
					.map((v) -> v.getKey())
					.findAny().orElse(-1);
		}
	}
	
	/**
	 * Remove the channel for the given symbol
	 * @param symbol
	 * @return
	 */
	public boolean removeChannelForSymbol(final BitfinexStreamSymbol symbol) {
		final int channel = getChannelForSymbol(symbol);
		
		if(channel != -1) {
			synchronized (channelIdSymbolMap) {
				channelIdSymbolMap.remove(channel);
			}
			
			return true;
		}
		
		return false;
	}
	
	/**
	 * Perform a reconnect
	 * @return
	 */
	public synchronized boolean reconnect() {
		try {
			logger.info("Performing reconnect");
			authenticated = false;
			
			// Invalidate old data
			quoteManager.invalidateTickerHeartbeat();
			orderManager.clear();
			positionManager.clear();
			
			websocketEndpoint.close();
			websocketEndpoint.connect();
			
			executeAuthentification();
			resubscribeChannels();

			updateConnectionHeartbeat();
			
			return true;
		} catch (Exception e) {
			logger.error("Got exception while reconnect", e);
			websocketEndpoint.close();
			return false;
		}
	}

	/**
	 * Resubscribe the old ticker
	 * @throws InterruptedException
	 * @throws APIException
	 */
	private void resubscribeChannels() throws InterruptedException, APIException {
		final Map<Integer, BitfinexStreamSymbol> oldChannelIdSymbolMap = new HashMap<>();

		synchronized (channelIdSymbolMap) {
			oldChannelIdSymbolMap.putAll(channelIdSymbolMap);
			channelIdSymbolMap.clear();
			channelIdSymbolMap.notifyAll();
		}
		
		// Resubscribe channels
		for(BitfinexStreamSymbol symbol : oldChannelIdSymbolMap.values()) {
			if(symbol instanceof BitfinexCurrencyPair) {
				sendCommand(new SubscribeTickerCommand((BitfinexCurrencyPair) symbol));
			} else if(symbol instanceof BitfinexCandlestickSymbol) {
				sendCommand(new SubscribeCandlesCommand((BitfinexCandlestickSymbol) symbol));
			} else if(symbol instanceof TradeOrderbookConfiguration) {
				sendCommand(new SubscribeTradingOrderbookCommand((TradeOrderbookConfiguration) symbol));
			} else {
				logger.error("Unknown stream symbol: {}", symbol);
			}
		}
		
		logger.info("Waiting for streams to resubscribe");
		int execution = 0;
		
		synchronized (channelIdSymbolMap) {		
			while(channelIdSymbolMap.size() != oldChannelIdSymbolMap.size()) {
				
				if(execution > 10) {
					
					// Restore old map for reconnect
					synchronized (channelIdSymbolMap) {
						channelIdSymbolMap.clear();
						channelIdSymbolMap.putAll(oldChannelIdSymbolMap);
					}
					
					throw new APIException("Subscription of ticker failed");
				}
				
				channelIdSymbolMap.wait(500);
				execution++;	
			}
		}
	}
	
	/**
	 * Place a new order
	 * @throws APIException 
	 */
	public void placeOrder(final BitfinexOrder order) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Executing new order {}", order);
		final OrderCommand orderCommand = new OrderCommand(order);
		sendCommand(orderCommand);
	}
	
	/**
	 * Cancel the given order
	 * @param cid
	 * @param date
	 * @throws APIException 
	 */
	public void cancelOrder(final long id) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Cancel order with id {}", id);
		final CancelOrderCommand cancelOrder = new CancelOrderCommand(id);
		sendCommand(cancelOrder);
	}
	
	/**
	 * Cancel the given order group
	 * @param cid
	 * @param date
	 * @throws APIException 
	 */
	public void cancelOrderGroup(final int id) throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		logger.info("Cancel order group {}", id);
		final CancelOrderGroupCommand cancelOrder = new CancelOrderGroupCommand(id);
		sendCommand(cancelOrder);
	}
	
	/**
	 * Get the last heartbeat value
	 * @return
	 */
	public AtomicLong getLastHeatbeat() {
		return lastHeatbeat;
	}
	
	/**
	 * Get the API key
	 * @return
	 */
	public String getApiKey() {
		return apiKey;
	}
	
	/**
	 * Get the API secret
	 * @return
	 */
	public String getApiSecret() {
		return apiSecret;
	}
	
	/**
	 * Get all wallets
	 * @return 
	 * @throws APIException 
	 */
	public Collection<Wallet> getWallets() throws APIException {
		
		throwExceptionIfUnauthenticated();
		
		synchronized (walletTable) {
			return Collections.unmodifiableCollection(walletTable.values());
		}
	}
	
	/**
	 * Get all wallets
	 * @return 
	 * @throws APIException 
	 */
	public Table<String, String, Wallet> getWalletTable() throws APIException {
		return walletTable;
	}

	/**
	 * Throw a new exception if called on a unauthenticated connection
	 * @throws APIException
	 */
	private void throwExceptionIfUnauthenticated() throws APIException {
		if(! authenticated) {
			throw new APIException("Unable to perform operation on an unauthenticated connection");
		}
	}
	
	/**
	 * Get the ticker manager
	 * @return
	 */
	public QuoteManager getQuoteManager() {
		return quoteManager;
	}
	
	/**
	 * Get the connection ready latch
	 * @return
	 */
	public CountDownLatch getConnectionReadyLatch() {
		return connectionReadyLatch;
	}

	/**
	 * Get the executor service
	 * @return
	 */
	public ExecutorService getExecutorService() {
		return executorService;
	}
	
	/**
	 * Get the order manager
	 * @return
	 */
	public OrderManager getOrderManager() {
		return orderManager;
	}
	
	/**
	 * Get the trading orderbook manager
	 * @return
	 */
	public TradingOrderbookManager getTradingOrderbookManager() {
		return tradingOrderbookManager;
	}

	/**
	 * Get the position manager
	 * @return
	 */
	public PositionManager getPositionManager() {
		return positionManager;
	}
}

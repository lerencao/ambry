package com.github.ambry.rest;

import com.github.ambry.router.AsyncWritableChannel;
import com.github.ambry.router.Callback;
import com.github.ambry.router.FutureResult;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONException;
import org.json.JSONObject;


/**
 * Implementation of {@link RestRequest} that can be used in tests.
 * <p/>
 * The underlying request metadata is in the form of a {@link JSONObject} that contains the following fields: -
 * 1. "restMethod" - {@link RestMethod} - the rest method required.
 * 2. "uri" - String - the uri.
 * 3. "headers" - {@link JSONObject} - all the headers as key value pairs.
 * <p/>
 * Headers:
 * 1. "contentLength" - the length of content accompanying this request. Defaults to 0.
 * <p/>
 * This also contains the content of the request. This content can be streamed out through the read operations.
 */
public class MockRestRequest implements RestRequest {
  /**
   * List of "events" (function calls) that can occur inside MockRestRequest.
   */
  public static enum Event {
    GetRestMethod,
    GetPath,
    GetUri,
    GetArgs,
    GetSize,
    ReadInto,
    IsOpen,
    Close,
    GetMetricsTracker
  }

  /**
   * Callback that can be used to listen to events that happen inside MockRestRequest.
   * <p/>
   * Please *do not* write tests that check for events *not* arriving. Events will not arrive if there was an exception
   * in the function that triggers the event or inside the function that notifies listeners.
   */
  public interface EventListener {

    /**
     * Called when an event (function call) finishes successfully in MockRestRequest. Does *not* trigger if the event
     * (function) fails.
     * @param mockRestRequest the {@link MockRestRequest} where the event occurred.
     * @param event the {@link Event} that occurred.
     */
    public void onEventComplete(MockRestRequest mockRestRequest, Event event);
  }

  // main fields
  public static String REST_METHOD_KEY = "restMethod";
  public static String URI_KEY = "uri";
  public static String HEADERS_KEY = "headers";

  // header fields
  public static String CONTENT_LENGTH_HEADER_KEY = "Content-Length";

  private final RestMethod restMethod;
  private final URI uri;
  private final Map<String, List<String>> args = new HashMap<String, List<String>>();
  private final ReentrantLock contentLock = new ReentrantLock();
  private final List<ByteBuffer> requestContents;
  private final AtomicBoolean channelOpen = new AtomicBoolean(true);
  private final List<EventListener> listeners = new ArrayList<EventListener>();
  private final RestRequestMetricsTracker restRequestMetricsTracker = new RestRequestMetricsTracker();

  private volatile AsyncWritableChannel writeChannel = null;
  private volatile ReadIntoCallbackWrapper callbackWrapper = null;

  /**
   * Create a MockRestRequest.
   * @param data the request metadata with the fields required.
   * @param requestContents contents of the request, if any. Can be null and can be added later via
   *                          {@link #addContent(ByteBuffer)}. Add a null {@link ByteBuffer} at the end to signify end
   *                          of content.
   * @throws IllegalArgumentException if the {@link RestMethod} required is not recognized.
   * @throws JSONException if there is an exception retrieving required fields.
   * @throws UnsupportedEncodingException if some parts of the URI are not in a format that can be decoded.
   * @throws URISyntaxException if there is a syntax error in the URI.
   */
  public MockRestRequest(JSONObject data, List<ByteBuffer> requestContents)
      throws JSONException, UnsupportedEncodingException, URISyntaxException {
    restRequestMetricsTracker.nioMetricsTracker.markRequestReceived();
    this.restMethod = RestMethod.valueOf(data.getString(REST_METHOD_KEY));
    this.uri = new URI(data.getString(URI_KEY));
    JSONObject headers = data.has(HEADERS_KEY) ? data.getJSONObject(HEADERS_KEY) : null;
    populateArgs(headers);
    if (requestContents != null) {
      this.requestContents = requestContents;
    } else {
      this.requestContents = new LinkedList<ByteBuffer>();
    }
  }

  @Override
  public RestMethod getRestMethod() {
    onEventComplete(Event.GetRestMethod);
    return restMethod;
  }

  @Override
  public String getPath() {
    onEventComplete(Event.GetPath);
    return uri.getPath();
  }

  @Override
  public String getUri() {
    onEventComplete(Event.GetUri);
    return uri.toString();
  }

  @Override
  public Map<String, List<String>> getArgs() {
    onEventComplete(Event.GetArgs);
    return args;
  }

  /**
   * Returns the value of the ambry specific content length header ({@link RestUtils.Headers#BLOB_SIZE}. If there is
   * no such header, returns length in the "Content-Length" header. If there is no such header, returns 0.
   * <p/>
   * This function does not individually count the bytes in the content (it is not possible) so the bytes received may
   * actually be different if the stream is buggy or the client made a mistake. Do *not* treat this as fully accurate.
   * @return the size of content as defined in headers. Might not be actual length of content if the stream is buggy.
   */
  @Override
  public long getSize() {
    long contentLength;
    if (args.get(RestUtils.Headers.BLOB_SIZE) != null) {
      contentLength = Long.parseLong(args.get(RestUtils.Headers.BLOB_SIZE).get(0));
    } else {
      contentLength =
          args.get(CONTENT_LENGTH_HEADER_KEY) != null ? Long.parseLong(args.get(CONTENT_LENGTH_HEADER_KEY).get(0)) : 0;
    }
    onEventComplete(Event.GetSize);
    return contentLength;
  }

  @Override
  public Future<Long> readInto(AsyncWritableChannel asyncWritableChannel, Callback<Long> callback) {
    ReadIntoCallbackWrapper tempWrapper = new ReadIntoCallbackWrapper(callback);
    contentLock.lock();
    try {
      if (!channelOpen.get()) {
        tempWrapper.invokeCallback(new ClosedChannelException());
      } else if (writeChannel != null) {
        throw new IllegalStateException("ReadableStreamChannel cannot be read more than once");
      }
      Iterator<ByteBuffer> bufferIterator = requestContents.iterator();
      while (bufferIterator.hasNext()) {
        ByteBuffer buffer = bufferIterator.next();
        writeContent(asyncWritableChannel, tempWrapper, buffer);
        bufferIterator.remove();
      }
      callbackWrapper = tempWrapper;
      writeChannel = asyncWritableChannel;
    } finally {
      contentLock.unlock();
    }
    onEventComplete(Event.ReadInto);
    return tempWrapper.futureResult;
  }

  @Override
  public boolean isOpen() {
    onEventComplete(Event.IsOpen);
    return channelOpen.get();
  }

  @Override
  public void close()
      throws IOException {
    channelOpen.set(false);
    onEventComplete(Event.Close);
  }

  @Override
  public RestRequestMetricsTracker getMetricsTracker() {
    onEventComplete(Event.GetMetricsTracker);
    return restRequestMetricsTracker;
  }

  /**
   * Register to be notified about events that occur in this MockRestRequest.
   * @param listener the listener that needs to be notified of events.
   */
  public MockRestRequest addListener(EventListener listener) {
    if (listener != null) {
      synchronized (listeners) {
        listeners.add(listener);
      }
    }
    return this;
  }

  /**
   * Adds some content in the form of {@link ByteBuffer} to this RestRequest. This content will be available to read
   * through the read operations. To indicate end of content, add a null ByteBuffer.
   * @throws ClosedChannelException if request channel has been closed.
   */
  public void addContent(ByteBuffer content)
      throws IOException {
    if (!RestMethod.POST.equals(getRestMethod()) && content != null) {
      throw new IllegalStateException("There is no content expected for " + getRestMethod());
    } else if (!isOpen()) {
      throw new ClosedChannelException();
    } else {
      contentLock.lock();
      try {
        if (!isOpen()) {
          throw new ClosedChannelException();
        } else if (writeChannel != null) {
          writeContent(writeChannel, callbackWrapper, content);
        } else {
          requestContents.add(content);
        }
      } finally {
        contentLock.unlock();
      }
    }
  }

  /**
   * Writes the provided {@code content} to the given {@code writeChannel}.
   * @param writeChannel the {@link AsyncWritableChannel} to write the {@code content} to.
   * @param callbackWrapper the {@link ReadIntoCallbackWrapper} for the read operation.
   * @param content the piece of {@link ByteBuffer} that needs to be written to the {@code writeChannel}.
   */
  private void writeContent(AsyncWritableChannel writeChannel, ReadIntoCallbackWrapper callbackWrapper,
      ByteBuffer content) {
    ContentWriteCallback writeCallback;
    if (content == null) {
      writeCallback = new ContentWriteCallback(true, callbackWrapper);
      content = ByteBuffer.allocate(0);
    } else {
      writeCallback = new ContentWriteCallback(false, callbackWrapper);
    }
    writeChannel.write(content, writeCallback);
  }

  /**
   * Adds all headers and parameters in the URL as arguments.
   * @param headers headers sent with the request.
   * @throws UnsupportedEncodingException if an argument key or value cannot be URL decoded.
   */
  private void populateArgs(JSONObject headers)
      throws JSONException, UnsupportedEncodingException {
    if (headers != null) {
      // add headers. Handles headers with multiple values.
      Iterator<String> headerKeys = headers.keys();
      while (headerKeys.hasNext()) {
        String headerKey = headerKeys.next();
        String headerValue = JSONObject.NULL.equals(headers.get(headerKey)) ? null : headers.getString(headerKey);
        addOrUpdateArg(headerKey, headerValue);
      }
    }

    // decode parameters in the URI. Handles parameters without values and multiple values for the same parameter.
    if (uri.getQuery() != null) {
      for (String parameterValue : uri.getQuery().split("&")) {
        int idx = parameterValue.indexOf("=");
        String key = idx > 0 ? parameterValue.substring(0, idx) : parameterValue;
        String value = idx > 0 ? parameterValue.substring(idx + 1) : null;
        addOrUpdateArg(key, value);
      }
    }
  }

  /**
   * Adds a {@code key}, {@code value} pair to args after URL decoding them. If {@code key} already exists,
   * {@code value} is added to a list of values.
   * @param key the key of the argument.
   * @param value the value of the argument.
   * @throws UnsupportedEncodingException if {@code key} or {@code value} cannot be URL decoded.
   */
  private void addOrUpdateArg(String key, String value)
      throws UnsupportedEncodingException {
    key = URLDecoder.decode(key, "UTF-8");
    if (value != null) {
      value = URLDecoder.decode(value, "UTF-8");
    }
    if (!args.containsKey(key)) {
      args.put(key, new LinkedList<String>());
    }
    args.get(key).add(value);
  }

  /**
   * Notify listeners of events.
   * <p/>
   * Please *do not* write tests that check for events *not* arriving. Events will not arrive if there was an exception
   * in the function that triggers the event or inside this function.
   * @param event the {@link Event} that just occurred.
   */
  private void onEventComplete(Event event) {
    synchronized (listeners) {
      for (EventListener listener : listeners) {
        try {
          listener.onEventComplete(this, event);
        } catch (Exception ee) {
          // too bad.
        }
      }
    }
  }
}

/**
 * Callback for each write into the given {@link AsyncWritableChannel}.
 */
class ContentWriteCallback implements Callback<Long> {
  private final boolean isLast;
  private final ReadIntoCallbackWrapper callbackWrapper;

  /**
   * Creates a new instance of ContentWriteCallback.
   * @param isLast if this is the last piece of content for this request.
   * @param callbackWrapper the {@link ReadIntoCallbackWrapper} that will receive updates of bytes read and one that
   *                        should be invoked in {@link #onCompletion(Long, Exception)} if {@code isLast} is
   *                        {@code true} or exception passed is not null.
   */
  public ContentWriteCallback(boolean isLast, ReadIntoCallbackWrapper callbackWrapper) {
    this.isLast = isLast;
    this.callbackWrapper = callbackWrapper;
  }

  /**
   * Updates the number of bytes read and invokes {@link ReadIntoCallbackWrapper#invokeCallback(Exception)} if
   * {@code exception} is not {@code null} or if this is the last piece of content in the request.
   * @param result The result of the request. This would be non null when the request executed successfully
   * @param exception The exception that was reported on execution of the request
   */
  @Override
  public void onCompletion(Long result, Exception exception) {
    callbackWrapper.updateBytesRead(result);
    if (exception != null || isLast) {
      callbackWrapper.invokeCallback(exception);
    }
  }
}

/**
 * Wrapper for callbacks provided to {@link MockRestRequest#readInto(AsyncWritableChannel, Callback)}.
 */
class ReadIntoCallbackWrapper {
  /**
   * The {@link Future} where the result of {@link MockRestRequest#readInto(AsyncWritableChannel, Callback)} will
   * eventually be updated.
   */
  public final FutureResult<Long> futureResult = new FutureResult<Long>();

  private final Callback<Long> callback;
  private final AtomicLong totalBytesRead = new AtomicLong(0);
  private final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

  /**
   * Creates an instance of ReadIntoCallbackWrapper with the given {@code callback}.
   * @param callback the {@link Callback} to invoke on operation completion.
   */
  public ReadIntoCallbackWrapper(Callback<Long> callback) {
    this.callback = callback;
  }

  /**
   * Updates the number of bytes that have been successfully read into the given {@link AsyncWritableChannel}.
   * @param delta the number of bytes read in the current invocation.
   * @return the total number of bytes read until now.
   */
  public long updateBytesRead(long delta) {
    return totalBytesRead.addAndGet(delta);
  }

  /**
   * Invokes the callback and updates the future once this is called. This function ensures that the callback is invoked
   * just once.
   * @param exception the {@link Exception}, if any, to pass to the callback.
   */
  public void invokeCallback(Exception exception) {
    if (callbackInvoked.compareAndSet(false, true)) {
      futureResult.done(totalBytesRead.get(), exception);
      if (callback != null) {
        callback.onCompletion(totalBytesRead.get(), exception);
      }
    }
  }
}

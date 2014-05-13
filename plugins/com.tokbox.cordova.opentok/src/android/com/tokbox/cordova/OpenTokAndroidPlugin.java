package com.tokbox.cordova;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import com.opentok.android.Connection;
import com.opentok.android.OpentokError;
import com.opentok.android.Publisher;
import com.opentok.android.PublisherKit;
import com.opentok.android.Session;
import com.opentok.android.Stream;
import com.opentok.android.Subscriber;
import com.opentok.android.SubscriberKit;


public class OpenTokAndroidPlugin extends CordovaPlugin implements Session.Listener{
  private String apiKey;
  private String sessionId;
  protected Session mSession;
  public static final String TAG = "OTPlugin";
  public boolean sessionConnected;
  public boolean publishCalled; // we need this because creating publisher before sessionConnected = crash
  public RunnablePublisher myPublisher;
  public HashMap<String, CallbackContext> myEventListeners;
  public HashMap<String, Connection> connectionCollection;
  public HashMap<String, Stream> streamCollection;
  public HashMap<String, RunnableSubscriber> subscriberCollection;

  static JSONObject viewList = new JSONObject();
  static CordovaInterface _cordova;
  static CordovaWebView _webView;


  public class RunnableUpdateViews implements Runnable{
    public JSONArray mProperty;
    public View mView;
    public ArrayList<RunnableUpdateViews> allStreamViews;


    public class CustomComparator implements Comparator<RunnableUpdateViews> {
      @Override
        public int compare(RunnableUpdateViews object1, RunnableUpdateViews object2) {
          return object2.getZIndex() - object1.getZIndex();
        }
    }

    public void updateZIndices(){
      allStreamViews =  new ArrayList<RunnableUpdateViews>();
      for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet() ) { 
        allStreamViews.add( entry.getValue() ); 
      }
      if( myPublisher != null ){
        allStreamViews.add( myPublisher ); 
      }
      Collections.sort( allStreamViews, new CustomComparator() );

      for( RunnableUpdateViews viewContainer : allStreamViews ){
        ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
        if (null != parent) {
          parent.removeView( viewContainer.mView );
          parent.addView(viewContainer.mView );
        }
      }
    }

    public int getZIndex(){
      try{
        return mProperty.getInt(5); 
      }catch( Exception e ) {
        return 0;
      }
    }

    @SuppressLint("NewApi")
    @Override
      public void run() {
        try{
          Log.i( TAG, "updating view in ui runnable" + mProperty.toString() );
          Log.i( TAG, "updating view in ui runnable " + mView.toString() );
          mView.setY( (float) mProperty.getInt(1) );
          mView.setX( (float) mProperty.getInt(2) );
          ViewGroup.LayoutParams params = mView.getLayoutParams();
          params.height = mProperty.getInt(4);
          params.width = mProperty.getInt(3);
          mView.setLayoutParams(params);
          updateZIndices();
        }catch( Exception e ){
          Log.i(TAG, "error when trying to retrieve properties while resizing properties");
        }
      }
  }

  public class RunnablePublisher extends RunnableUpdateViews implements Publisher.Listener{
    //  property contains: [stream.streamId, position.top, position.left, width, height, subscribeToVideo, zIndex] )
    public Publisher mPublisher;

    public RunnablePublisher( JSONArray args ){
      this.mProperty = args;

      // prevent dialog box from showing because it causes crash
      SharedPreferences prefs = cordova.getActivity().getApplicationContext().getSharedPreferences("permissions",
          Context.MODE_PRIVATE);
      Editor edit = prefs.edit();
      edit.clear();
      edit.putBoolean("opentok.publisher.accepted", true);
      edit.commit();
    }

    public void setPropertyFromArray( JSONArray args ){
      this.mProperty = args;
    }

    public void startPublishing(){
      cordova.getActivity().runOnUiThread( this );
    }

    public void run() {
      Log.i(TAG, "view running on UIVIEW!!!");
      if( mPublisher == null ){
        ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
        mPublisher = new Publisher(cordova.getActivity().getApplicationContext(), this, "publisher" );
        try{
          if( this.mProperty.getString(8) != null && !(this.mProperty.getString(8).equalsIgnoreCase("front") ) ){
            mPublisher.swapCamera();
          }
        }catch( Exception e ){
          Log.i(TAG, "error when trying to retrieve cameraName property");
        }
        this.mView = mPublisher.getView();
        frame.addView( this.mView );
        mSession.publish(mPublisher);
      }
      super.run();
    }

    @Override
    public void changedCamera(PublisherKit arg0, int arg1) {
      // TODO Auto-generated method stub
      
    }

    @Override
    public void error(PublisherKit arg0, OpentokError arg1) {
      // TODO Auto-generated method stub
      
    }

    @Override
    public void streamCreated(PublisherKit arg0, Stream arg1) {
      Log.i(TAG, "publisher stream received");
      streamCollection.put(arg1.getStreamId(), arg1);
      triggerStreamCreated( arg1, "publisherEvents");
    }

    @Override
    public void streamDestroyed(PublisherKit arg0, Stream arg1) {
      streamCollection.remove( arg1.getStreamId() );
      triggerStreamDestroyed( arg1, "publisherEvents");
    }

  }

  public class RunnableSubscriber extends RunnableUpdateViews implements Subscriber.Listener{
    //  property contains: [stream.streamId, position.top, position.left, width, height, subscribeToVideo, zIndex] )
    public Subscriber mSubscriber;
    public Stream mStream;

    public RunnableSubscriber( JSONArray args, Stream stream ){
      this.mProperty = args;
      mStream = stream;
      cordova.getActivity().runOnUiThread( this );
    }

    public void setPropertyFromArray( JSONArray args ){
      this.mProperty = args;
    }
    
    public void removeStreamView(){
      ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
      frame.removeView( this.mView );
      mSubscriber.destroy();
    }

    public void run() {
      if( mSubscriber == null ){
        mSubscriber = new Subscriber(cordova.getActivity(), mStream, this);
        ViewGroup frame = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
        this.mView = mSubscriber.getView();
        frame.addView( this.mView );
        mSession.subscribe(mSubscriber);
        Log.i(TAG, "subscriber view is added to parent view!");
      }
      super.run();
    }

    @Override
    public void connected(SubscriberKit arg0) {
      // TODO Auto-generated method stub
      Log.i(TAG, "subscriber is connected");
      this.run();
    }

    @Override
    public void disconnected(SubscriberKit arg0) {
      // TODO Auto-generated method stub
    }

    @Override
    public void error(SubscriberKit arg0, OpentokError arg1) {
      // TODO Auto-generated method stub

      Log.e(TAG, "subscriber exception: " + arg1.getMessage() + ", stream id: " + arg0.getStream().getStreamId() );
    }

    @Override
    public void videoDataReceived(SubscriberKit arg0) {
      // TODO Auto-generated method stub
      
    }

    @Override
    public void videoDisabled(SubscriberKit arg0) {
      // TODO Auto-generated method stub

      Log.i(TAG, "subscriber video disabled, stream id: " + arg0.getStream().getStreamId());
    }
  }

  @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
      _cordova = cordova;
      _webView = webView;
      Log.d(TAG, "Initialize Plugin");
      // By default, get a pointer to mainView and add mainView to the viewList as it always exists (hold cordova's view)
      if (!viewList.has("mainView")) {
        // Cordova view is not in the viewList so add it.
        try {
          viewList.put("mainView", webView);
          Log.d(TAG, "Found CordovaView ****** " + webView);
        } catch (JSONException e) {
          // Error handle (this should never happen!)
          Log.e(TAG, "Critical error. Failed to retrieve Cordova's view");
        }
      }

      // set OpenTok states
      publishCalled = false;
      sessionConnected = false;
      myEventListeners = new HashMap<String, CallbackContext>();
      connectionCollection = new HashMap<String, Connection>();
      streamCollection = new HashMap<String, Stream>();
      subscriberCollection = new HashMap<String, RunnableSubscriber>();

      super.initialize(cordova, webView);
    }

  @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
      Log.i( TAG, action );
      // TB Methods
      if( action.equals("initPublisher")){
        myPublisher = new RunnablePublisher( args );
      }else if( action.equals( "destroyPublisher" )){

      }else if( action.equals( "initSession" )){
        Log.i( TAG, "created new session with data: "+args.toString());
        apiKey = args.getString(0);
        mSession = new Session(this.cordova.getActivity().getApplicationContext(), args.getString(1), this );
      
      // publisher methods
      }else if( action.equals( "publishAudio") ){
        String val = args.getString(0);
        boolean publishAudio = true;
        if( val.equalsIgnoreCase("false") ){
          publishAudio = false;
        }
        Log.i(TAG, "setting publishAudio");
        myPublisher.mPublisher.setPublishAudio( publishAudio );
      }else if( action.equals( "publishVideo") ){
        String val = args.getString(0);
        boolean publishVideo = true;
        if( val.equalsIgnoreCase("false") ){
          publishVideo = false;
        }
        Log.i(TAG, "setting publishVideo");
        myPublisher.mPublisher.setPublishVideo( publishVideo );
        
      // session Methods
      }else if( action.equals( "addEvent" )){
        Log.i( TAG, "adding new event - " + args.getString(0));
        myEventListeners.put( args.getString(0), callbackContext);
      }else if( action.equals( "connect" )){
        Log.i( TAG, "connect command called");
        mSession.connect( apiKey, args.getString(0));
      }else if( action.equals( "disconnect" )){
        mSession.disconnect();
      }else if( action.equals( "publish" )){
        if( sessionConnected ){
          Log.i( TAG, "publisher is publishing" );
          myPublisher.startPublishing();
        }
      }else if( action.equals( "signal" )){

        ArrayList<Connection> connections = new ArrayList<Connection>();
        for( String e : args.getString(2).split(" ")){
          Connection c = connectionCollection.get(e);
          if( c!= null){
            connections.add(c);
          }
        }
        mSession.sendSignal(args.getString(0), args.getString(1), connections);
      }else if( action.equals( "unpublish" )){

      }else if( action.equals( "unsubscribe" )){

      }else if( action.equals( "subscribe" )){
        Log.i( TAG, "subscribe command called");
        Log.i( TAG, "subscribe data: " + args.toString() );
        Stream stream = streamCollection.get( args.getString(0) );
        RunnableSubscriber runsub = new RunnableSubscriber( args, stream ); 
        subscriberCollection.put(stream.getStreamId(), runsub);
      }else if( action.equals( "updateView" )){
        if( args.getString(0).equals("TBPublisher") && myPublisher != null ){
          Log.i( TAG, "updating view for publisher" );
          myPublisher.setPropertyFromArray(args);
          cordova.getActivity().runOnUiThread(myPublisher);
        }else{
          RunnableSubscriber runsub = subscriberCollection.get( args.getString(0) );
          if( runsub != null ){
            runsub.setPropertyFromArray( args );
            cordova.getActivity().runOnUiThread(runsub);
          }
        }
      }else if( action.equals( "exceptionHandler" )){

      }
      return true;
    }

  public void alertUser( String message){
    // 1. Instantiate an AlertDialog.Builder with its constructor
    AlertDialog.Builder builder = new AlertDialog.Builder( cordova.getActivity());
    builder.setMessage( message ).setTitle( "TokBox Message");
    AlertDialog dialog = builder.create();
  }



  @Override
  public void addPublisher(Session arg0, PublisherKit arg1) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void connected(Session arg0) {
    Log.i(TAG, "session connected, triggering sessionConnected Event. My Cid is: "+ 
        mSession.getConnection().getConnectionId()    );      
    sessionConnected = true;

    JSONObject data = new JSONObject();
    try{
      data.put("status", "connected");
      JSONObject connection = createDataFromConnection( mSession.getConnection() );
      data.put("connection", connection);
    }catch (JSONException e) {}
    triggerJSEvent( "sessionEvents", "sessionConnected", data);
  }

  @Override
  public void connectionCreated(Session arg0, Connection arg1) {
    Log.i(TAG, "connectionCreated");   

    connectionCollection.put(arg1.getConnectionId(), arg1);

    JSONObject data= new JSONObject();
    try{
      JSONObject connection = createDataFromConnection( arg1 );
      data.put("connection", connection);
    }catch (JSONException e) {}
    triggerJSEvent( "sessionEvents", "connectionCreated", data);
  }

  @Override
  public void connectionDestroyed(Session arg0, Connection arg1) {Log.i(TAG, "connection dropped: " + arg1.getConnectionId());

  connectionCollection.remove( arg1.getConnectionId() );
    JSONObject data= new JSONObject();
    try{
      JSONObject connection = createDataFromConnection( arg1 );
      data.put("connection", connection);
    }catch (JSONException e) {}
    triggerJSEvent( "sessionEvents", "connectionDestroyed", data);
  }

  @Override
  public void disconnected(Session arg0) {
    ViewGroup parent = (ViewGroup) cordova.getActivity().findViewById(android.R.id.content);
    for (Map.Entry<String, RunnableSubscriber> entry : subscriberCollection.entrySet() ) { 
      if (null != parent) {
        parent.removeView( entry.getValue().mView  );
      }
    }
    if( myPublisher != null ){
      parent.removeView( myPublisher.mView  );
    }
    
    JSONObject data = new JSONObject();   
    try{
      data.put("reason", "clientDisconnected");
    }catch (JSONException e) {}

    triggerJSEvent( "sessionEvents", "sessionDisconnected", data);
  }

  @Override
  public void receivedStream(Session arg0, Stream arg1) {
    Log.i(TAG, "stream received");
    streamCollection.put(arg1.getStreamId(), arg1);
    triggerStreamCreated( arg1, "sessionEvents");
  }

  @Override
  public void droppedStream(Session arg0, Stream arg1) {
    Log.i(TAG, "session dropped stream");
    streamCollection.remove( arg1.getStreamId() );
    RunnableSubscriber subscriber = subscriberCollection.get( arg1.getStreamId() );
    subscriber.removeStreamView();    
    subscriberCollection.remove( arg1.getStreamId() );
  
    triggerStreamDestroyed( arg1, "sessionEvents");
  }

  @Override
  public void error(Session arg0, OpentokError arg1) {
    // TODO Auto-generated method stub

    Log.e(TAG, "session exception: " + arg1.getMessage());
    alertUser("session error "+arg1.getMessage());
  }

  @Override
  public void onSignal(Session arg0, String arg1, String arg2, Connection arg3) {
    JSONObject data= new JSONObject();
    try{
      data.put("type", arg1);
      data.put("data", arg2);
      data.put("connectionId", arg3.getConnectionId());
      triggerJSEvent( "sessionEvents", "signalReceived", data);
    }catch (JSONException e) {}
  }

  @Override
  public void removePublisher(Session arg0, PublisherKit arg1) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void streamChangeHasAudio(Session arg0, Stream arg1, int arg2) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void streamChangeHasVideo(Session arg0, Stream arg1, int arg2) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void streamChangeVideoDimensions(Session arg0, Stream arg1, int arg2,
      int arg3) {
    // TODO Auto-generated method stub
    
  }
  
  // Helper Methods
  public void triggerStreamDestroyed( Stream arg1, String eventType ){
    JSONObject data= new JSONObject();
    try{
      JSONObject stream = createDataFromStream( arg1 );
      data.put("stream", stream);
      triggerJSEvent( "sessionEvents", "streamDestroyed", data);
    }catch (JSONException e) {}
  }
  public void triggerStreamCreated( Stream arg1, String eventType ){
    JSONObject data= new JSONObject();
    try{
      JSONObject stream = createDataFromStream( arg1 );
      data.put("stream", stream);
      triggerJSEvent( eventType, "streamCreated", data);
    }catch (JSONException e) {}
    
    Log.i(TAG, "stream received done");
  }
  public JSONObject createDataFromConnection( Connection arg1 ){
    JSONObject connection = new JSONObject();
    
    try{
      connection.put("connectionId", arg1.getConnectionId());
      connection.put("creationTime", arg1.getCreationTime());
      connection.put("data", arg1.getData());
    }catch (JSONException e) {}
    return connection;
  }
  public JSONObject createDataFromStream( Stream arg1 ){
    JSONObject stream = new JSONObject();
    try{
      stream.put("connectionId", arg1.getConnection().getConnectionId() );
      stream.put("creationTime", arg1.getCreationTime() );
      stream.put("fps", -999);
      stream.put("hasAudio", arg1.hasAudio());
      stream.put("hasVideo", arg1.hasVideo());
      stream.put("name", arg1.getName());
      stream.put("streamId", arg1.getStreamId());
    }catch (JSONException e) {}
    return stream;
  }
  public void triggerJSEvent(String event, String type, JSONObject data ){
    JSONObject message = new JSONObject();       

    try{
      message.put("eventType", type);
      message.put("data", data);
    }catch (JSONException e) {}
    
    PluginResult myResult = new PluginResult(PluginResult.Status.OK, message);
    myResult.setKeepCallback(true);
    myEventListeners.get(event).sendPluginResult(myResult);
  }
}

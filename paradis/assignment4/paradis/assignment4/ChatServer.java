/**
 * Vera Nygren
 * Inspired by the EchoServer2 written by Peter Idestam-Almquist, in base functionality
 */
package paradis.assignment4;

import java.net.Socket;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.Collection;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ChatServer implements Runnable {
	private static final int PORT = 8000;

	/**
	 * No clue what is a good estimate of what any computer running this should handle? 
	 * some sort of multiplier based on available processors seems reasonable - to ensure performance.
	 */
	private static final int MAX_CLIENTS = Runtime.getRuntime().availableProcessors()*8;
	private static final Executor EXECUTOR = Executors.newFixedThreadPool(MAX_CLIENTS);
	private static Long idCounter = 0L;

	/**
	 * Yes this is a complicated data structure. 
	 * However, the concept behind it isn't that complicated: 
	 * Per chatclient connected to the server, there is a message queue for each connected client
	 * That is, in every client there is a mailbox for every client, containing messages.
	 * 
	 * This way messages can be removed from the queues as theyve been processed by each separate client.
	 * Timestamps are used to make sure there is an universal order to the messages. 
	 * 
	 * So, access is supposed to work like this:
	 * 		A client collecting messages from others: 
	 * 			1. get the value from the key associated with your own threads client. 
	 * 			2. look through all of the entries in the value to see if there are new messages
	 * 			3. if there are, remove those messages and then send them to the client.
	 *		A client sharing a message to the other clients:
	 *			1. go through each of the entries associated with all other threads clients
	 *			2. In the value of that entry, find the entry associated with your own threads client.
	 *			3. Add the message you want to share to the value of that entry. 
	 */
	private static final ConcurrentHashMap
							<Long, ConcurrentHashMap
								<Long, ConcurrentHashMap
									<LocalDateTime, String>>> mailingSystem
		= new ConcurrentHashMap<>();


	private final Socket clientSocket;
	private final Long id; 

	/**
	 * 
	 * @param clientSocket
	 * @param id Needs to be an unique ID to not get the messages all messed up. 
	 */
    private ChatServer(Socket clientSocket) {
		this.clientSocket = clientSocket;
		this.id = idCounter;
		idCounter++;
	}

	/**
	 * Shares a message with all other clients connected to the server.
	 * @param client the name of the client sending the message
	 * @param newMessage the message to be shared
	 */
	private void share(String client, String newMessage) {
		LocalDateTime now = LocalDateTime.now();
		String message = client + ": " + newMessage;
		mailingSystem.get(id).forEachValue(1, x -> x.put(now, message));
		//add new message and who it was from to datastructure or queue or whatever
	}

	/**
	 * Collects any new messages that have been sent to this client.
	 * @return A collection of Strings in the shape of "clientName: message", ordered by the time they arrived to server.
	 */
	private Collection<String> collect(){
		ConcurrentHashMap<LocalDateTime, String> newMessages = new ConcurrentHashMap<>();
		ConcurrentHashMap<LocalDateTime, String> newPostBox = new ConcurrentHashMap<>();
		mailingSystem.forEachValue(1, x -> newMessages.putAll(x.replace(id, newPostBox)));
		TreeMap<LocalDateTime, String> sortedNewMessages = new TreeMap<>();
		sortedNewMessages.putAll(newMessages);
		return sortedNewMessages.values();
	}

	/**
	 * Adds a client to the data structure within the server, making it possible to share messages to other clients
	 * and collecting messages from the other clients. 
	 */
	private void addClient(){
		ConcurrentHashMap<LocalDateTime, String> messages = new ConcurrentHashMap<>();
		ConcurrentHashMap<Long, ConcurrentHashMap<LocalDateTime, String>> postBox = new ConcurrentHashMap<>();
		postBox.put(id, messages);
		//this line below doesnt work.
		for(ConcurrentHashMap<Long, ConcurrentHashMap<LocalDateTime, String>> map: mailingSystem.values()){
			postBox.putAll(map);
		}

		mailingSystem.put(id, postBox);	
		mailingSystem.forEachValue(1, x -> x.put(id, new ConcurrentHashMap<>()));
		//new clients arent added to old ones? or old ones cant get new messages
	}

	/**
	 * Removes a client from the server data structure. 
	 */
	private void removeClient(){
		mailingSystem.remove(id);
		mailingSystem.forEachValue(1, x -> x.remove(id));
	}

    public void run(){
        SocketAddress remoteSocketAddress = clientSocket.getRemoteSocketAddress();
		SocketAddress localSocketAddress = clientSocket.getLocalSocketAddress();
		System.out.println("New Connection at " + remoteSocketAddress 
			+ " (" + localSocketAddress + ").");

		try (
			PrintWriter socketWriter = new PrintWriter(clientSocket.getOutputStream(), true);                   
			BufferedReader socketReader = new BufferedReader(
				new InputStreamReader(clientSocket.getInputStream()))
			) {
			socketWriter.println("Please enter name");
			String inputLine = socketReader.readLine();
			String clientName = inputLine;
			addClient();
			System.out.println("Name of Client at: \"" + remoteSocketAddress + " set to " + clientName);
			socketWriter.println("Welcome to the chat, " + clientName + "!");
			inputLine = "";
			while (inputLine != null) {
				if(!"".equals(inputLine)){
					share(clientName, inputLine);
				}
	

				//until there is a new message from the chatclient, 
				//look for messages from other clients
				while(!socketReader.ready()){
					//to prevent a ridiculous amount of collect()-calls:
					Thread.sleep(1);
					Collection<String> newMessages = collect();
					for(String message : newMessages){
						socketWriter.println(message);
						System.out.println("Sent: \"" + message + "\" to " 
						+ clientName + " " + remoteSocketAddress);
					}
				}
				inputLine = socketReader.readLine();
				System.out.println("Received: \"" + inputLine + "\" from " 
				+ clientName + " " + remoteSocketAddress);

            }
			System.out.println("Closing connection " + remoteSocketAddress 
				+ " (" + localSocketAddress + ").");
			removeClient();
		} catch (Exception e) {
			System.out.println(e);
		}


    }

    public static void main(String[] args) {
		System.out.println("ChatServer started.");

		Socket clientSocket = null;
		try (
			ServerSocket serverSocket = new ServerSocket(PORT);
		){
            
			SocketAddress serverSocketAddress = serverSocket.getLocalSocketAddress();
			System.out.println("Listening (" + serverSocketAddress + ").");
            
			while (true) {
				clientSocket = serverSocket.accept();     
				EXECUTOR.execute(new ChatServer(clientSocket));
			}
        } 
		catch (Exception exception) {
            System.out.println(exception);
        }

    }
}

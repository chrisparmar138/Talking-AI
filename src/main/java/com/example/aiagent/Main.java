package com.example.aiagent;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

/**
 * Main entry point for the AI Agent application.
 * This class initializes and starts an embedded Jetty web server.
 */
public class Main {

    /**
     * The main method that starts the server.
     * @param args Command line arguments (not used).
     * @throws Exception if the server fails to start.
     */
    public static void main(String[] args) throws Exception {
        // Create a new Jetty server instance on port 8080.
        Server server = new Server(8080);

        // Create a servlet context handler. This will manage the servlets.
        // The context path "/" means it will handle requests for the root of the server.
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Attach the context handler to the server.
        server.setHandler(context);

        // Add our ChatServlet to the context handler and map it to the "/chat" path.
        // All requests to http://localhost:8080/chat will be handled by ChatServlet.
        context.addServlet(new ServletHolder(new ChatServlet()), "/chat");

        System.out.println("Starting Jetty server on port 8080...");

        // Start the server.
        server.start();

        System.out.println("Server started successfully.");

        // Wait for the server to be joined, which effectively keeps the main thread alive.
        server.join();
    }
}
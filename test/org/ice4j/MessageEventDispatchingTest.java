/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

import java.net.*;
import java.util.*;

import junit.framework.*;

import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * Test event dispatching for both client and server.
 *`
 * @author Emil Ivov
 */
public class MessageEventDispatchingTest extends TestCase
{
    /**
     * The stack that we are using for the tests.
     */
    StunStack stunStack = null;

    /**
     * The address of the client.
     */
    TransportAddress clientAddress
        = new TransportAddress("127.0.0.1", 5216, Transport.UDP);

    /**
     * The Address of the server.
     */
    TransportAddress serverAddress
        = new TransportAddress("127.0.0.1", 5255, Transport.UDP);

    /**
     * The address of the second server.
     */
    TransportAddress serverAddress2
        = new TransportAddress("127.0.0.1", 5259, Transport.UDP);

    /**
     * The socket that the client is using.
     */
    DatagramSocket  clientSock = null;

    /**
     * The socket that the server is using
     */
    DatagramSocket  serverSock = null;

    /**
     * The second server socket.
     */
    DatagramSocket serverSock2 = null;

    /**
     * The request that we will be sending in this test.
     */
    Request  bindingRequest = null;

    /**
     * The response that we will be sending in response to the above request.
     */
    Response bindingResponse = null;

    /**
     * The request collector that we use to wait for requests.
     */
    PlainRequestCollector requestCollector = null;

    /**
     * The responses collector that we use to wait for responses.
     */
    PlainResponseCollector responseCollector = null;

    /**
     * junit setup method.
     *
     * @throws Exception if anything goes wrong.
     */
    protected void setUp() throws Exception
    {
        super.setUp();

        stunStack = StunStack.getInstance();

        clientSock = new SafeCloseDatagramSocket(clientAddress);
        serverSock = new SafeCloseDatagramSocket(serverAddress);
        serverSock2 = new SafeCloseDatagramSocket(serverAddress2);

        stunStack.addSocket(clientSock);
        stunStack.addSocket(serverSock);
        stunStack.addSocket(serverSock2);

        bindingRequest = MessageFactory.createBindingRequest();
        bindingResponse = MessageFactory.createBindingResponse(
            clientAddress, clientAddress, serverAddress);

        requestCollector = new PlainRequestCollector();
        responseCollector = new PlainResponseCollector();

    }

    /**
     * junit tear down method.
     *
     * @throws Exception if anything goes wrong.
     */
    protected void tearDown() throws Exception
    {
        stunStack.removeSocket(clientAddress);
        stunStack.removeSocket(serverAddress);
        stunStack.removeSocket(serverAddress2);

        clientSock.close();
        serverSock.close();
        serverSock2.close();

        requestCollector = null;
        responseCollector = null;

        super.tearDown();
    }

    /**
     * Test timeout events.
     *
     * @throws Exception upon a stun failure
     */
    public void testClientTransactionTimeouts() throws Exception
    {
        String oldRetransValue = System.getProperty(
                            "org.ice4j.MAX_RETRANSMISSIONS");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS", "1");
        stunStack.sendRequest(bindingRequest, serverAddress, clientAddress,
                        responseCollector);
        responseCollector.waitForTimeout();

        assertEquals(
            "No timeout was produced upon expiration of a client transaction",
            responseCollector.receivedResponses.size(), 1);

        assertEquals(
            "No timeout was produced upon expiration of a client transaction",
            responseCollector.receivedResponses.get(0), "timeout");

        //restore the retransmissions prop in case others are counting on
        //defaults.
        if(oldRetransValue != null)
            System.getProperty( "org.ice4j.MAX_RETRANSMISSIONS",
                                oldRetransValue);
        else
            System.clearProperty("org.ice4j.MAX_RETRANSMISSIONS");
    }

    /**
     * Test reception of Message events.
     *
     * @throws java.lang.Exception upon any failure
     */
    public void testEventDispatchingUponIncomingRequests() throws Exception
    {
        //prepare to listen
        stunStack.addRequestListener(requestCollector);
        //send
        stunStack.sendRequest(bindingRequest, serverAddress, clientAddress,
                                            responseCollector);
        //wait for retransmissions
        requestCollector.waitForRequest();

        //verify
        assertTrue("No MessageEvents have been dispatched",
            requestCollector.receivedRequests.size() == 1);
    }

    /**
     * Test that reception of Message events is only received for accesspoints
     * that we have been registered for.
     *
     * @throws java.lang.Exception upon any failure
     */
    public void testSelectiveEventDispatchingUponIncomingRequests()
        throws Exception
    {
        //prepare to listen
        stunStack.addRequestListener(serverAddress, requestCollector);

        PlainRequestCollector requestCollector2 = new PlainRequestCollector();
        stunStack.addRequestListener(serverAddress2, requestCollector2);

        //send
        stunStack.sendRequest(bindingRequest, serverAddress2, clientAddress,
                                            responseCollector);
        //wait for retransmissions
        requestCollector.waitForRequest();
        requestCollector2.waitForRequest();

        //verify
        assertTrue(
            "A MessageEvent was received by a non-interested selective listener",
            requestCollector.receivedRequests.size() == 0);
        assertTrue(
            "No MessageEvents have been dispatched for a selective listener",
            requestCollector2.receivedRequests.size() == 1);
    }


    /**
     * Makes sure that we receive response events.
     * @throws Exception if we screw up.
     */
    public void testServerResponseRetransmissions() throws Exception
    {
        //prepare to listen
        stunStack.addRequestListener(serverAddress, requestCollector);
        //send
        stunStack.sendRequest(bindingRequest, serverAddress, clientAddress,
                                            responseCollector);

        //wait for the message to arrive
        requestCollector.waitForRequest();

        StunMessageEvent evt = requestCollector.receivedRequests.get(0);
        byte[] tid = evt.getMessage().getTransactionID();
        stunStack.sendResponse(tid, bindingResponse, serverAddress,
                                             clientAddress);

        //wait for retransmissions
        responseCollector.waitForResponse();

        //verify that we got the response.
        assertTrue(
            "There were no retransmissions of a binding response",
            responseCollector.receivedResponses.size() == 1 );
    }

    /**
     * A utility class we use to collect incoming requests.
     */
    private class PlainRequestCollector implements RequestListener
    {
        /** all requests we've received so far. */
        public Vector<StunMessageEvent> receivedRequests
            = new Vector<StunMessageEvent>();

        /**
         * Stores incoming requests.
         *
         * @param evt the event containing the incoming request.
         */
        public void requestReceived(StunMessageEvent evt)
        {
            synchronized (this)
            {
                receivedRequests.add(evt);
                notifyAll();
            }
        }

        public void waitForRequest()
        {
            synchronized(this)
            {
                if (receivedRequests.size() > 0)
                    return;
                try
                {
                    wait(50);
                }
                catch (InterruptedException e)
                {}
            }
        }
    }

    /**
     * A utility class we use to collect incoming responses.
     */
    private class PlainResponseCollector implements ResponseCollector
    {
        /**
         *
         */
        public Vector<Object> receivedResponses = new Vector<Object>();

        /**
         * Stores incoming requests.
         *
         * @param responseEvt the event containing the incoming request.
         */
        public void processResponse(StunMessageEvent responseEvt)
        {
            synchronized(this)
            {
                receivedResponses.add(responseEvt);
                notifyAll();
            }
        }

        /**
         * Indicates that no response has been received.
         */
        public void processTimeout()
        {
            synchronized(this)
            {
                receivedResponses.add(new String("timeout"));
                notifyAll();
            }
        }

        /**
         * Waits for a short period of time for a response to arrive
         */
        public void waitForResponse()
        {
            synchronized(this)
            {
                try
                {
                    if (receivedResponses.size() > 0)
                        return;
                    wait(50);
                }
                catch (InterruptedException e)
                {}
            }
        }

        /**
         * Waits for a long period of time for a timeout trigger to fire.
         */
        public void waitForTimeout()
        {
            synchronized(this)
            {
                try
                {
                    if (receivedResponses.size() > 0)
                        return;
                    wait(12000);
                }
                catch (InterruptedException e)
                {}
            }
        }

    }
}
<h1 align="center">
	<img
		alt="bytesocks"
		src="https://i.imgur.com/hZy89YS.png">
</h1>

<h3 align="center">
  bytesocks is a fast & lightweight WebSocket server.
</h3>

It allows clients to create "**channels**", connect using the WebSocket protocol and **exchange messages with other clients** in the same channel.

In many ways bytesocks is the "socket" sibling of [bytebin](https://github.com/lucko/bytebin). bytebin accepts http get and post requests which allows clients to exchange data asynchronously (client A posts, then client B reads later), whereas bytesocks uses sockets to send messages synchronously (in real time and bidirectionally, client A sends and client B receives).


## Running bytesocks

The easiest way to spin up a bytesocks instance is using Docker. Images are automatically created and published to GitHub for each commit/release.

Assuming you're in the bytesocks directory, just run:
```bash
$ docker compose up
```

You should then (hopefully!) be able to access the application at `http://localhost:3000/`.

It's that easy!

## API Usage

### Create a channel

To create a channel, send an HTTP `GET` request to `/create`.

A unique key that identifies the newly created channel will be returned. You can find it:
* In the response `Location` header.
* In the response body, encoded as JSON - `{"key": "aabbcc"}`.

### Join a channel

Send an HTTP `GET` request to `/{key}` with the headers:
```
Connection: Upgrade
Upgrade: websocket
```

At this stage it's probably easier to find an HTTP client library that supports web sockets instead of reimplementing the protocol yourself!

## License
MIT, have fun!

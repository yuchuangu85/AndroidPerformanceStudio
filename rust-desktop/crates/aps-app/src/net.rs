//! GPUI installs the same host transport as the analysis CLI.
pub(crate) use aps_ai::curl_client::CurlHttpClient;

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use futures::io::AsyncReadExt;
    use gpui_kit::http_client::{AsyncBody, HttpClient, Request, Response, StatusCode};
    use std::io::Read;
    use std::io::Write;
    use std::net::TcpListener;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc as std_mpsc;

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-http-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    pub(crate) fn serve_once(
        response: impl AsRef<[u8]> + Send + 'static,
    ) -> (String, std_mpsc::Receiver<Vec<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let (sender, receiver) = std_mpsc::channel();
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut buffer = [0u8; 4096];
            loop {
                let read = stream.read(&mut buffer).unwrap();
                if read == 0 {
                    break;
                }
                request.extend_from_slice(&buffer[..read]);
                if request.windows(4).any(|window| window == b"\r\n\r\n") {
                    break;
                }
            }
            let header_end = request
                .windows(4)
                .position(|window| window == b"\r\n\r\n")
                .map(|index| index + 4)
                .unwrap_or(request.len());
            let headers = String::from_utf8_lossy(&request[..header_end]).to_ascii_lowercase();
            let length = headers
                .lines()
                .find_map(|line| line.strip_prefix("content-length:"))
                .and_then(|value| value.trim().parse::<usize>().ok())
                .unwrap_or(0);
            while request.len() < header_end + length {
                let read = stream.read(&mut buffer).unwrap();
                if read == 0 {
                    break;
                }
                request.extend_from_slice(&buffer[..read]);
            }
            let _ = sender.send(request);
            stream.write_all(response.as_ref()).unwrap();
            stream.flush().unwrap();
        });
        (format!("http://{address}/v1/models"), receiver)
    }

    fn read_body(response: Response<AsyncBody>) -> String {
        let body = futures::executor::block_on(async move {
            let mut body = Vec::new();
            response.into_body().read_to_end(&mut body).await.unwrap();
            body
        });
        String::from_utf8_lossy(&body).into_owned()
    }

    #[test]
    fn get_returns_the_head_and_streams_the_body() {
        let directory = TestDirectory::new();
        let (uri, received) = serve_once(
            b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: 27\r\n\r\n{\"data\":[{\"id\":\"gpt-5\"}]}",
        );
        let client = CurlHttpClient::new(directory.0.clone());
        let response = futures::executor::block_on(client.get(&uri, AsyncBody::empty(), false))
            .expect("the local request succeeds");
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "application/json");
        assert_eq!(read_body(response), "{\"data\":[{\"id\":\"gpt-5\"}]}");
        let request = received.recv().unwrap();
        assert!(String::from_utf8_lossy(&request).starts_with("GET /v1/models HTTP/1.1"));
    }

    #[test]
    fn post_forwards_the_json_body_and_keeps_the_secret_out_of_argv() {
        let directory = TestDirectory::new();
        let (uri, received) = serve_once(
            b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\nContent-Length: 12\r\n\r\ndata: done\n\n",
        );
        let client = CurlHttpClient::new(directory.0.clone());
        let request = Request::builder()
            .method("POST")
            .uri(uri)
            .header("Authorization", "Bearer sk-test-1234567890")
            .header("Content-Type", "application/json")
            .body(AsyncBody::from("{\"input\":\"hi\"}"))
            .unwrap();
        let response =
            futures::executor::block_on(client.send(request)).expect("the local request succeeds");
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(read_body(response), "data: done\n\n");
        let raw = received.recv().unwrap();
        let text = String::from_utf8_lossy(&raw).to_ascii_lowercase();
        assert!(text.starts_with("post /v1/models"));
        assert!(text.contains("authorization: bearer sk-test-1234567890"));
        assert!(text.contains("{\"input\":\"hi\"}"));
        // The staged body file is cleaned up once the request finishes.
        let staged = std::fs::read_dir(directory.0.join("http-bodies"))
            .map(|entries| entries.count())
            .unwrap_or_default();
        assert_eq!(staged, 0);
    }

    #[test]
    fn a_failed_transport_reports_an_error_instead_of_an_empty_response() {
        let directory = TestDirectory::new();
        let client = CurlHttpClient::new(directory.0.clone());
        let uri = "http://127.0.0.1:1/v1/models";
        let error = futures::executor::block_on(client.get(uri, AsyncBody::empty(), false))
            .err()
            .expect("an unreachable endpoint fails");
        assert!(!error.to_string().is_empty());
    }
}

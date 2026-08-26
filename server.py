import os
from flask import Flask, send_file, abort
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# Path to the compiled DEX payload
PAYLOAD_PATH = "payload.dex"

@app.route('/download_payload', methods=['GET'])
def download_payload():
    if os.path.exists(PAYLOAD_PATH):
        print(f"Serving {PAYLOAD_PATH}...")
        return send_file(PAYLOAD_PATH, mimetype='application/octet-stream')
    else:
        print(f"Error: {PAYLOAD_PATH} not found. Please run build_payload.ps1 first.")
        abort(404, description="Payload not found on server")

@app.route('/status', methods=['GET'])
def status():
    return {"status": "online", "payload_ready": os.path.exists(PAYLOAD_PATH)}

if __name__ == '__main__':
    # Listen on all interfaces so the emulator can connect
    app.run(host='0.0.0.0', port=5000, debug=True)

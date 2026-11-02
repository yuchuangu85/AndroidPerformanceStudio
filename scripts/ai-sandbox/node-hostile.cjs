const fs = require('fs');
const assert = require('assert');
const cp = require('child_process');
const net = require('net');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));

function denied(action) {
    try {
        action();
    } catch (error) {
        if (['EACCES', 'EPERM', 'ENOENT', 'EROFS'].includes(error.code)) return;
        throw error;
    }
    throw new Error('forbidden operation succeeded');
}

function socketDenied(options) {
    return new Promise((resolve, reject) => {
        const socket = net.connect(options);
        socket.once('connect', () => { socket.destroy(); reject(new Error('network allowed')); });
        socket.once('error', error => {
            socket.destroy();
            if (['EPERM', 'EACCES'].includes(error.code)) resolve();
            else reject(error);
        });
        socket.setTimeout(500, () => { socket.destroy(); reject(new Error('network timed out instead of denied')); });
    });
}

async function main() {
    switch (input.case) {
    case 'boundary':
        denied(() => fs.readFileSync(input.sentinel));
        denied(() => fs.openSync(input.sentinel, 'w'));
        denied(() => fs.openSync('/input/skill', 'w'));
        denied(() => fs.openSync('/dev/shm/forbidden', 'w'));
        denied(() => fs.openSync('/dev/forbidden', 'w'));
        for (const directory of ['/tmp', '/work']) {
            const output = directory + '/output';
            fs.writeFileSync(output, 'positive-control');
            assert.strictEqual(fs.readFileSync(output, 'utf8'), 'positive-control');
            fs.unlinkSync(output);
            fs.symlinkSync(input.sentinel, directory + '/host-link');
            denied(() => fs.readFileSync(directory + '/host-link'));
            denied(() => fs.openSync(directory + '/host-link', 'w'));
        }
        for (const file of [
            '/usr/share/nodejs/cjs-module-lexer/lexer.js',
            '/usr/share/nodejs/cjs-module-lexer/dist/lexer.js',
            '/usr/share/nodejs/undici/undici-fetch.js',
            '/usr/share/nodejs/acorn/dist/acorn.js',
            '/usr/share/nodejs/acorn-walk/dist/walk.js',
        ]) {
            assert(fs.readFileSync(file).length > 0);
            denied(() => fs.openSync(file, 'w'));
        }
        for (const file of ['/usr/share/nodejs/acorn/package.json', '/usr/share/doc', '/usr/local', '/Users']) {
            assert(!fs.existsSync(file));
        }
        assert.deepStrictEqual(fs.readdirSync('/home'), []);
        break;
    case 'network':
        await socketDenied({host: '127.0.0.1', port: 9});
        await socketDenied({host: '::1', port: 9});
        await socketDenied({path: '/tmp/blocked.sock'});
        break;
    case 'fork-descendants': {
        const child = cp.spawnSync(process.execPath, ['-e', 'process.exit(0)']);
        assert(child.error && child.error.code === 'EPERM');
        const native = cp.spawnSync('/usr/bin/true');
        assert(native.error && native.error.code === 'EPERM');
        break;
    }
    case 'memory':
        fs.writeSync(1, 'APS_MEMORY_READY\n');
        try {
            Buffer.alloc(320 * 1024 * 1024);
        } catch (error) {
            assert(error instanceof RangeError || error.code === 'ENOMEM');
            break;
        }
        throw new Error('memory allocation exceeded budget');
    case 'scratch':
        for (const directory of ['/tmp', '/work']) {
            let full = false;
            for (let index = 0; index < 128; index++) {
                try { fs.writeFileSync(directory + '/' + index, Buffer.alloc(4096)); }
                catch (error) { assert.strictEqual(error.code, 'ENOSPC'); full = true; break; }
            }
            assert(full, 'tmpfs quota exceeded');
        }
        break;
    case 'file-size': {
        process.on('SIGXFSZ', () => {});
        const fd = fs.openSync('/work/large', 'w');
        try {
            let limited = false;
            try { fs.writeSync(fd, Buffer.from('x'), 0, 1, 65536); }
            catch (error) { assert.strictEqual(error.code, 'EFBIG'); limited = true; }
            assert(limited, 'file-size limit exceeded');
        } finally { fs.closeSync(fd); }
        break;
    }
    case 'cpu':
        for (;;) {}
    case 'output':
        fs.writeSync(1, Buffer.alloc(40000, 'o'));
        fs.writeSync(2, Buffer.alloc(40000, 'e'));
        return;
    case 'cancel':
        fs.writeSync(1, 'APS_READY\n');
        await new Promise(resolve => setTimeout(resolve, 60000));
        break;
    default:
        throw new Error('unknown fixture');
    }
    console.log('APS_PASS ' + input.case);
}

main().catch(error => { console.error(error); process.exitCode = 1; });

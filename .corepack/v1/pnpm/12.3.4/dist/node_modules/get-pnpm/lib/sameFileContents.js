import fs from 'node:fs';
const CHUNK_SIZE = 64 * 1024;
/**
 * Whether two paths hold the same bytes.
 *
 * Compared rather than hashed: the answer is usually "no" at the first
 * differing byte, and there is nothing to gain from reading further.
 *
 * `b` is the untrusted side — a path something else may hold — so anything
 * other than a readable regular file of the same size is simply not the same
 * file, rather than an error.
 */
export function sameFileContents(a, b) {
    const sizeA = fs.statSync(a).size;
    const statB = fs.lstatSync(b, { throwIfNoEntry: false });
    if (statB?.isFile() !== true || statB.size !== sizeA)
        return false;
    let fdA;
    let fdB;
    try {
        fdA = fs.openSync(a, 'r');
        fdB = fs.openSync(b, 'r');
        const bufferA = Buffer.alloc(CHUNK_SIZE);
        const bufferB = Buffer.alloc(CHUNK_SIZE);
        while (true) {
            const readA = fs.readSync(fdA, bufferA, 0, CHUNK_SIZE, null);
            const readB = fs.readSync(fdB, bufferB, 0, CHUNK_SIZE, null);
            if (readA !== readB)
                return false;
            if (readA === 0)
                return true;
            if (!bufferA.subarray(0, readA).equals(bufferB.subarray(0, readB)))
                return false;
        }
    }
    catch {
        return false;
    }
    finally {
        if (fdA !== undefined)
            fs.closeSync(fdA);
        if (fdB !== undefined)
            fs.closeSync(fdB);
    }
}

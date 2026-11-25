'use strict';

// usage: node compare.js <js dump dir> <java dump dir>
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const [jsDir, javaDir] = process.argv.slice(2);
let failed = 0;
const files = fs.readdirSync(jsDir).filter(function (f) { return f.endsWith('.json'); }).sort();

files.forEach(function (file) {
    const js = JSON.parse(fs.readFileSync(path.join(jsDir, file), 'utf8'));
    const javaFile = path.join(javaDir, file);
    if (!fs.existsSync(javaFile)) {
        failed++;
        console.log('MISSING ' + file);

        return;
    }
    const java = JSON.parse(fs.readFileSync(javaFile, 'utf8'));
    try {
        assert.deepStrictEqual(java, js);
    } catch (e) {
        failed++;
        console.log('DIFF ' + file + firstDifference(js, java, ''));
    }
});

function firstDifference(a, b, at) {
    if (typeof a !== typeof b || Array.isArray(a) !== Array.isArray(b) || a === null || b === null) {
        return a === b ? '' : ' at ' + at + ': js=' + short(a) + ' java=' + short(b);
    }
    if (typeof a !== 'object') {
        return a === b ? '' : ' at ' + at + ': js=' + short(a) + ' java=' + short(b);
    }
    const keys = Array.from(new Set(Object.keys(a).concat(Object.keys(b))));
    for (const k of keys) {
        if (!(k in a) || !(k in b)) return ' at ' + at + '.' + k + ': js=' + short(a[k]) + ' java=' + short(b[k]);
        const d = firstDifference(a[k], b[k], at + '.' + k);
        if (d) return d;
    }

    return '';
}

function short(v) {
    const s = JSON.stringify(v);

    return s === undefined ? 'undefined' : (s.length > 100 ? s.slice(0, 100) + '...' : s);
}

console.log((files.length - failed) + ' of ' + files.length + ' language definitions match highlight.js');
process.exit(failed ? 1 : 0);

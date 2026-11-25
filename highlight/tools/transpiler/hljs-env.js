'use strict';

/**
 * Builds a minimal `hljs` object from a highlight.js source checkout so that
 * language definitions can be evaluated in Node without a build step.
 *
 * Only what language definitions use at definition time is provided: the
 * common regexes and modes, `inherit`, `COMMENT`, `SHEBANG`, `END_SAME_AS_BEGIN`,
 * `getLanguage` and `requireLanguage`.
 *
 * The ES modules of highlight.js are evaluated with a small loader that
 * understands the forms of `import` and `export` they use.
 */

const fs = require('fs');
const path = require('path');

const IMPORT_RE = /^import\s+(?:\*\s+as\s+([\w$]+)|\{([^}]*)\}|([\w$]+))\s+from\s+['"]([^'"]+)['"];?[ \t]*$/gm;

/**
 * Evaluates an ES module and the modules it imports.
 *
 * @returns the exports of the module, the default export as `default`
 */
function loadModule(file, cache) {
    if (cache.has(file)) {
        return cache.get(file);
    }
    const exportsObject = {};
    cache.set(file, exportsObject);

    let code = fs.readFileSync(file, 'utf8');
    const params = [];
    const values = [];
    code = code.replace(IMPORT_RE, function (m, namespace, named, defaultName, from) {
        let target = path.resolve(path.dirname(file), from);
        if (!target.endsWith('.js') && !target.endsWith('.json')) target += '.js';
        if (target.endsWith('.json')) {
            params.push(namespace || defaultName);
            values.push(JSON.parse(fs.readFileSync(target, 'utf8')));

            return '';
        }
        const imported = loadModule(target, cache);
        if (namespace) {
            params.push(namespace);
            values.push(imported);
        } else if (defaultName) {
            params.push(defaultName);
            values.push(imported.default);
        } else {
            named.split(',').map(function (n) { return n.trim(); }).filter(Boolean).forEach(function (n) {
                const parts = n.split(/\s+as\s+/);
                params.push(parts[1] || parts[0]);
                values.push(imported[parts[0]]);
            });
        }

        return '';
    });

    const names = [];
    code = code.replace(/^export\s*\{([^}]*)\};?/gm, function (m, list) {
        list.split(',').map(function (n) { return n.trim(); }).filter(Boolean).forEach(function (n) { names.push(n); });

        return '';
    });
    code = code.replace(/^export (?:const|let|var|function|class) ([A-Za-z_$][\w$]*)/gm, function (m, name) {
        names.push(name);

        return m.slice('export '.length);
    });
    code = code.replace(/^export default /m, '__exports.default = ');

    const body = code + '\n' + names.map(function (n) { return '__exports.' + n + ' = ' + n + ';'; }).join('\n');
    // eslint-disable-next-line no-new-func
    new Function('__exports', ...params, body)(exportsObject, ...values);

    return exportsObject;
}

function deepFreeze(o) {
    Object.freeze(o);
    Object.getOwnPropertyNames(o).forEach(function (prop) {
        if (o[prop] !== null && typeof o[prop] === 'object' && !Object.isFrozen(o[prop])) {
            deepFreeze(o[prop]);
        }
    });

    return o;
}

/**
 * @param {string} hljsDir path to a highlight.js checkout
 */
function createHljs(hljsDir) {
    const libDir = path.join(hljsDir, 'src', 'lib');
    const langDir = path.join(hljsDir, 'src', 'languages');
    const cache = new Map();

    const utils = loadModule(path.join(libDir, 'utils.js'), cache);
    const MODES = loadModule(path.join(libDir, 'modes.js'), cache);

    const languages = Object.create(null);
    const aliases = Object.create(null);

    const hljs = {
        inherit: utils.inherit,
    };

    function loadDefinition(name) {
        const file = path.join(langDir, name + '.js');
        if (!fs.existsSync(file)) {
            return null;
        }

        return loadModule(file, cache).default;
    }

    function registerLanguage(name) {
        if (languages[name]) {
            return languages[name];
        }
        const fn = loadDefinition(name);
        if (!fn) {
            return null;
        }
        const lang = fn(hljs);
        lang.rawDefinition = fn.bind(null, hljs);
        languages[name] = lang;
        (lang.aliases || []).forEach(function (alias) {
            aliases[alias] = name;
        });

        return lang;
    }

    hljs.getLanguage = function (name) {
        name = (name || '').toLowerCase();

        return languages[name] || languages[aliases[name]] || registerLanguage(name);
    };

    hljs.requireLanguage = function (name) {
        const lang = hljs.getLanguage(name);
        if (lang) {
            return lang;
        }
        throw new Error("The '" + name + "' language is required, but not loaded.");
    };

    for (const key in MODES) {
        if (typeof MODES[key] === 'object') {
            deepFreeze(MODES[key]);
        }
    }
    Object.assign(hljs, MODES);

    return {
        hljs: hljs,
        /** Evaluates a language definition from scratch, as `rawDefinition()` would. */
        define: function (name) {
            return loadDefinition(name)(hljs);
        },
        languageNames: function () {
            return fs.readdirSync(langDir)
                .filter(function (f) { return f.endsWith('.js'); })
                .map(function (f) { return f.slice(0, -3); })
                .sort();
        },
    };
}

module.exports = { createHljs: createHljs };

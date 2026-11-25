'use strict';

/**
 * Transpiles highlight.js (v10) language definitions into Java classes.
 *
 * A highlight.js language is a function `function(hljs) { ...; return {...}; }`
 * written in a small subset of JavaScript. This tool translates that subset
 * into an equivalent Java class implementing `org.scrivo.highlight.LanguageDefinition`.
 *
 * Java is statically typed, so the transpiler infers the type of each value:
 *
 * - object literals are modes (`new Mode().begin(...)...`), unless they are
 *   used as keyword maps (`map("keyword", "...")`) or hold other data;
 * - arrays are `List<Mode>` or `List<String>` (`list(...)`), shared by
 *   reference like JavaScript arrays;
 * - regular expressions are strings holding the JS regex source; the Java
 *   engine translates JS regex syntax when compiling;
 * - functions declared in the definition become private static methods, the
 *   local variables they use are passed as extra arguments.
 *
 * Anything outside the supported subset throws, so that new constructs in
 * future highlight.js versions are noticed instead of silently mistranslated.
 * The Java compiler and the comparison of the evaluated definitions
 * (verify.sh) check the result.
 */

const acorn = require('acorn');

const INDENT = '\t';
const TAB_WIDTH = 4;
const MAX_LINE = 120;
/** Marks the start of a trailing line comment, so suffixes can be inserted before it. */
const COMMENT_MARK = '\u0000';
/** Marks a line that only holds a comment. */
const COMMENT_LINE = '\u0001';
/** javac folds constant strings, which may not exceed 65535 bytes. */
const MAX_CONSTANT = 20000;

const JAVA_RESERVED = new Set(('abstract assert boolean break byte case catch char class const continue default do ' +
    'double else enum extends final finally float for goto if implements import instanceof int interface long native ' +
    'new package private protected public return short static strictfp super switch synchronized this throw throws ' +
    'transient try void volatile while true false null var record yield sealed permits _').split(' '));

/** Names that may not be used for language classes or helper methods. */
const RESERVED_NAMES = new Set(['Mode', 'Hljs', 'LanguageDefinition', 'Language', 'List', 'Map', 'Registry', 'Js',
    'String', 'Object', 'Integer', 'Boolean', 'define']);

/** Mode properties and their types. */
const MODE_PROPS = {
    name: 'string',
    aliases: 'strings',
    case_insensitive: 'boolean',
    disableAutodetect: 'boolean',
    className: 'string',
    begin: 'string',
    end: 'string',
    beginKeywords: 'string',
    endsWithParent: 'boolean',
    endsParent: 'boolean',
    endSameAsBegin: 'boolean',
    lexemes: 'string',
    keywords: 'keywords',
    illegal: 'string',
    excludeBegin: 'boolean',
    excludeEnd: 'boolean',
    returnBegin: 'boolean',
    returnEnd: 'boolean',
    contains: 'modes',
    starts: 'mode',
    variants: 'modes',
    relevance: 'number',
    subLanguage: 'sublanguage',
    skip: 'boolean',
};

/** Keys that only occur in modes. */
const MODE_KEYS = ['begin', 'end', 'className', 'contains', 'variants', 'beginKeywords', 'keywords', 'starts',
    'relevance', 'illegal', 'endsWithParent', 'returnBegin', 'excludeBegin', 'excludeEnd', 'returnEnd', 'subLanguage',
    'endsParent', 'skip', 'lexemes', 'case_insensitive', 'aliases', 'name'];

const JAVA_TYPES = {
    string: 'String',
    number: 'Integer',
    boolean: 'Boolean',
    mode: 'Mode',
    modes: 'List<Mode>',
    strings: 'List<String>',
    map: 'Map<String, String>',
    objmap: 'Map<String, Object>',
    lang: 'Language',
};

class TranspileError extends Error {
    constructor(message, node) {
        super(message + (node && node.loc ? ' (line ' + node.loc.start.line + ')' : ''));
    }
}

/**
 * @param {string} languageId
 * @returns {string}
 */
function className(languageId) {
    const special = { '1c': 'OneC' };
    if (special[languageId]) {
        return special[languageId];
    }
    let name = languageId.split(/[^a-zA-Z0-9]+/).filter(Boolean)
        .map(function (p) { return p[0].toUpperCase() + p.slice(1); }).join('');
    if (/^[0-9]/.test(name) || JAVA_RESERVED.has(name.toLowerCase()) || RESERVED_NAMES.has(name)) {
        name = 'Lang' + name;
    }

    return name;
}

function javaName(name) {
    if (JAVA_RESERVED.has(name)) {
        return name + '_';
    }

    return name;
}

function javaType(type, node) {
    if (!JAVA_TYPES[type]) {
        throw new TranspileError('Cannot determine the Java type (' + type + ')', node);
    }

    return JAVA_TYPES[type];
}

// ---------------------------------------------------------------------------
// Java literal helpers

function javaString(value) {
    let out = '"';
    for (const ch of value) {
        const code = ch.codePointAt(0);
        if (ch === '\\') out += '\\\\';
        else if (ch === '"') out += '\\"';
        else if (ch === '\n') out += '\\n';
        else if (ch === '\r') out += '\\r';
        else if (ch === '\t') out += '\\t';
        else if (ch === '\b') out += '\\b';
        else if (ch === '\f') out += '\\f';
        else if (code < 0x20 || code === 0x7f || code === 0x2028 || code === 0x2029) {
            out += '\\u' + code.toString(16).padStart(4, '0');
        } else out += ch;
    }

    return out + '"';
}

/** Java processes unicode escapes everywhere, also in comments. */
function safeComment(text) {
    return text.replace(/\\u/g, '\\\\u');
}

// ---------------------------------------------------------------------------
// Comments

class Comments {
    constructor(comments, source) {
        this.comments = comments;
        this.source = source;
        this.used = new Set();
        this.lineStarts = [0];
        for (let i = 0; i < source.length; i++) {
            if (source.charCodeAt(i) === 10) this.lineStarts.push(i + 1);
        }
    }

    /** A trailing comment, to be appended to a line of code. */
    format(c) {
        if (c.type === 'Line') {
            return COMMENT_MARK + '//' + safeComment(c.value.replace(/\s+$/, ''));
        }

        return '/*' + safeComment(c.value).replace(/\s*\n\s*/g, ' ') + '*/';
    }

    /** Comments in [from, to) not yet used. */
    take(from, to) {
        const out = [];
        for (const c of this.comments) {
            if (c.start >= from && c.end <= to && !this.used.has(c)) {
                this.used.add(c);
                out.push(c);
            }
        }

        return out;
    }

    hasUnused(from, to) {
        return this.comments.some((c) => !this.used.has(c) && c.start > from && c.end < to);
    }

    lineOf(pos) {
        let lo = 0;
        let hi = this.lineStarts.length - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if (this.lineStarts[mid] <= pos) lo = mid;
            else hi = mid - 1;
        }

        return lo + 1;
    }

    /** Comment lines, indented with pad. */
    lines(c, pad) {
        if (c.type === 'Line') return [COMMENT_LINE + pad + '//' + safeComment(c.value.replace(/\s+$/, ''))];
        const body = safeComment(c.value).split('\n');

        return body.map(function (l, i) {
            const t = i === 0 ? '/*' + l : ' ' + l.replace(/^\s*/, '');

            return (pad + t).trimEnd();
        }).map(function (l, i, all) {
            return COMMENT_LINE + (i === all.length - 1 ? l + '*/' : l);
        });
    }
}

// ---------------------------------------------------------------------------
// Scope / usage analysis

function propKey(prop) {
    if (prop.computed) {
        throw new TranspileError('Computed property keys are not supported', prop);
    }
    if (prop.key.type === 'Identifier') return prop.key.name;
    if (prop.key.type === 'Literal') return String(prop.key.value);
    throw new TranspileError('Unsupported property key ' + prop.key.type, prop);
}

function rootName(node) {
    while (node.type === 'MemberExpression') {
        node = node.object;
    }

    return node.type === 'Identifier' ? node.name : null;
}

/**
 * Collects facts about the variables declared in a function body that tell
 * their types.
 */
function analyse(fnBody, params) {
    const info = {
        vars: new Map(),         // name -> {init, kind, declIndex}
        assignments: new Map(),  // name -> [value]
        pushed: new Map(),       // name -> [value]
        modeUse: new Set(),      // variables used where a mode (or a list of modes) is expected
        keywordUse: new Set(),   // object variables used as keyword maps
        stringListUse: new Set(),// arrays used as lists of strings
        functions: new Map(),    // name -> FunctionDeclaration / FunctionExpression
        calls: new Map(),        // function name -> [CallExpression]
    };

    params.forEach(function (p) {
        info.vars.set(p, { init: null, kind: 'param', declIndex: -1 });
    });

    fnBody.body.forEach(function (stmt, index) {
        if (stmt.type === 'VariableDeclaration') {
            stmt.declarations.forEach(function (d) {
                info.vars.set(d.id.name, { init: d.init, kind: 'var', declIndex: index });
                if (d.init && d.init.type === 'FunctionExpression') {
                    info.functions.set(d.id.name, d.init);
                }
            });
        } else if (stmt.type === 'FunctionDeclaration') {
            info.vars.set(stmt.id.name, { init: stmt, kind: 'function', declIndex: index });
            info.functions.set(stmt.id.name, stmt);
        }
    });

    function add(map, key, value) {
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(value);
    }

    function markModeUse(node) {
        if (node && node.type === 'Identifier') {
            info.modeUse.add(node.name);
        }
        if (node && node.type === 'ArrayExpression') {
            node.elements.forEach(markModeUse);
        }
    }

    function visit(node) {
        if (!node || typeof node.type !== 'string') {
            return;
        }
        if (node !== fnBody && (node.type === 'FunctionExpression' || node.type === 'FunctionDeclaration')) {
            // nested functions have their own scope, but they may use ours
        }

        if (node.type === 'Property') {
            const k = propKey(node);
            if (k === 'keywords' && node.value.type === 'Identifier') {
                info.keywordUse.add(node.value.name);
            } else if (MODE_PROPS[k] === 'modes' || MODE_PROPS[k] === 'mode') {
                markModeUse(node.value);
            } else if (MODE_PROPS[k] === 'strings' && node.value.type === 'Identifier') {
                info.stringListUse.add(node.value.name);
            }
        }

        if (node.type === 'ReturnStatement') {
            markModeUse(node.argument);
        }

        if (node.type === 'CallExpression') {
            const callee = node.callee;
            if (callee.type === 'Identifier') {
                add(info.calls, callee.name, node);
            }
            if (callee.type === 'MemberExpression') {
                const method = callee.property.name;
                if (['push', 'unshift'].includes(method) && callee.object.type === 'Identifier') {
                    node.arguments.forEach(function (a) { add(info.pushed, callee.object.name, a); });
                }
                if (callee.object.type === 'Identifier' && callee.object.name === 'hljs' && method === 'inherit') {
                    node.arguments.forEach(markModeUse);
                }
                if (callee.object.type === 'Identifier' && callee.object.name === 'Object' && method === 'assign') {
                    markModeUse(node.arguments[0]);
                }
                if (method === 'join' && callee.object.type === 'Identifier') {
                    info.stringListUse.add(callee.object.name);
                }
            }
        }

        if (node.type === 'AssignmentExpression') {
            if (node.left.type === 'Identifier') {
                add(info.assignments, node.left.name, node.right);
            } else if (node.left.type === 'MemberExpression') {
                const name = rootName(node.left);
                if (name && (node.left.object.type === 'MemberExpression' || node.left.computed)) {
                    // contains[0].contains = ...  -> array element is a mode
                    info.modeUse.add(name);
                }
                if (!node.left.computed && (MODE_PROPS[node.left.property.name] === 'modes' ||
                    MODE_PROPS[node.left.property.name] === 'mode')) {
                    markModeUse(node.right);
                    if (node.left.object.type === 'Identifier') {
                        info.modeUse.add(node.left.object.name);
                    }
                }
            }
        }

        for (const k of Object.keys(node)) {
            if (k === 'loc' || k === 'start' || k === 'end') continue;
            const child = node[k];
            if (Array.isArray(child)) {
                child.forEach(visit);
            } else if (child && typeof child.type === 'string') {
                visit(child);
            }
        }
    }

    visit(fnBody);

    return info;
}

/** Free identifiers referenced inside a function (excluding its params and locals). */
function freeVariables(fn) {
    const bound = new Set(fn.params.map(function (p) { return p.name; }));
    const free = new Set();

    (fn.body.body || []).forEach(function (node) {
        if (node.type === 'VariableDeclaration') {
            node.declarations.forEach(function (d) { bound.add(d.id.name); });
        }
        if (node.type === 'FunctionDeclaration') {
            bound.add(node.id.name);
        }
    });

    function visit(node, parent, key) {
        if (!node || typeof node.type !== 'string') return;
        if (node.type === 'Identifier') {
            const isProp = parent && parent.type === 'MemberExpression' && key === 'property' && !parent.computed;
            const isKey = parent && parent.type === 'Property' && key === 'key';
            if (!isProp && !isKey && !bound.has(node.name)) free.add(node.name);
            return;
        }
        if ((node.type === 'FunctionExpression' || node.type === 'ArrowFunctionExpression' ||
            node.type === 'FunctionDeclaration') && node !== fn) {
            freeVariables(node).forEach(function (v) {
                if (!bound.has(v)) free.add(v);
            });
            return;
        }
        for (const k of Object.keys(node)) {
            if (k === 'loc') continue;
            const child = node[k];
            if (Array.isArray(child)) child.forEach(function (c) { visit(c, node, k); });
            else if (child && typeof child.type === 'string') visit(child, node, k);
        }
    }
    visit(fn.body, null, null);

    return free;
}

// ---------------------------------------------------------------------------
// Emitter

class Emitter {
    constructor(source, comments, languageId) {
        this.source = source;
        this.comments = comments;
        this.languageId = languageId;
        this.scopes = [];
        this.methods = [];      // emitted helper methods
        this.methodInfo = new Map(); // function node -> {name, params, captured, returnType}
        this.imports = new Set();
        this.typing = new Set(); // guards against infinite recursion while typing
    }

    get scope() {
        return this.scopes[this.scopes.length - 1];
    }

    lookup(name) {
        for (let i = this.scopes.length - 1; i >= 0; i--) {
            if (this.scopes[i].info.vars.has(name)) {
                return { scope: this.scopes[i], decl: this.scopes[i].info.vars.get(name) };
            }
        }

        return null;
    }

    use(name) {
        this.imports.add(name);
    }

    // ---- typing ---------------------------------------------------------

    /**
     * @returns {'string'|'number'|'boolean'|'null'|'mode'|'modes'|'strings'|'map'|'objmap'|'lang'|'hljs'|'function'|'self'|'unknown'}
     */
    typeOf(node) {
        switch (node.type) {
            case 'Literal':
                if (node.regex) return 'string';
                if (node.value === null) return 'null';

                return typeof node.value;
            case 'TemplateLiteral':
                return 'string';
            case 'ArrayExpression':
                return this.arrayLiteralType(node);
            case 'ObjectExpression':
                return 'mode';
            case 'BinaryExpression':
                if (node.operator === '+') {
                    const l = this.typeOf(node.left);
                    const r = this.typeOf(node.right);

                    return (l === 'string' || r === 'string') ? 'string' : (l === 'number' && r === 'number' ? 'number' : 'unknown');
                }

                return 'unknown';
            case 'LogicalExpression': {
                const l = this.typeOf(node.left);

                return l === 'unknown' || l === 'null' ? this.typeOf(node.right) : l;
            }
            case 'Identifier':
                return this.identifierType(node.name, node);
            case 'MemberExpression':
                return this.memberType(node);
            case 'CallExpression':
                return this.callType(node);
            case 'FunctionExpression':
                return 'function';
            default:
                return 'unknown';
        }
    }

    arrayLiteralType(node) {
        let sawString = false;
        for (const el of node.elements) {
            if (el && el.type === 'Literal' && el.value === 'self') return 'modes';
            const t = el ? this.typeOf(el) : 'unknown';
            if (t === 'mode') return 'modes';
            if (t === 'string') sawString = true;
        }

        return sawString ? 'strings' : (node.elements.length ? 'unknown' : 'empty');
    }

    identifierType(name, node) {
        if (name === 'hljs') return 'hljs';
        if (name === 'undefined') return 'null';
        const found = this.lookup(name);
        if (!found) return 'unknown';
        const d = found.decl;
        if (d.kind === 'function') return 'function';
        if (d.kind === 'param') return d.type || 'unknown';
        if (d.type) return d.type;
        const key = found.scope.id + ':' + name;
        if (this.typing.has(key)) return 'unknown';
        this.typing.add(key);
        try {
            d.type = this.variableType(name, d, found.scope);
        } finally {
            this.typing.delete(key);
        }

        return d.type;
    }

    variableType(name, d, scope) {
        const info = scope.info;
        const init = d.init;
        const assigned = info.assignments.get(name) || [];
        if (init && init.type === 'FunctionExpression') return 'function';
        if (init && init.type === 'ObjectExpression') return this.objectVarType(name, init, scope);
        if (init && init.type === 'ArrayExpression') {
            const t = this.withScope(scope, () => this.arrayLiteralType(init));
            if (t === 'modes' || t === 'strings') return t;

            return this.withScope(scope, () => this.listTypeFromUse(name, info));
        }
        const candidates = (init ? [init] : []).concat(assigned);
        for (const c of candidates) {
            const t = this.withScope(scope, () => this.typeOf(c));
            if (t === 'empty') return this.withScope(scope, () => this.listTypeFromUse(name, info));
            if (t !== 'unknown' && t !== 'null') return t;
        }
        if (info.modeUse.has(name)) return 'mode';

        return 'unknown';
    }

    /** The type of an array variable, from the way it is used. */
    listTypeFromUse(name, info) {
        for (const v of info.pushed.get(name) || []) {
            const t = this.typeOf(v);
            if (t === 'mode') return 'modes';
            if (t === 'string') return 'strings';
        }
        if (info.stringListUse.has(name)) return 'strings';

        return 'modes';
    }

    objectVarType(name, init, scope) {
        const info = scope.info;
        let isMode = info.modeUse.has(name);
        if (!isMode && !info.keywordUse.has(name)) {
            // objects that look like modes are modes even when only read from
            const keys = init.properties.map(propKey);
            isMode = keys.length === 0 || keys.some(function (k) { return MODE_KEYS.includes(k); });
        }
        if (isMode) return 'mode';

        return this.withScope(scope, () => this.mapType(init));
    }

    mapType(node) {
        const allStrings = node.properties.every((p) => this.typeOf(p.value) === 'string');

        return allStrings ? 'map' : 'objmap';
    }

    withScope(scope, cb) {
        const index = this.scopes.indexOf(scope);
        if (index === this.scopes.length - 1 || index < 0) return cb();
        const saved = this.scopes;
        this.scopes = this.scopes.slice(0, index + 1);
        try {
            return cb();
        } finally {
            this.scopes = saved;
        }
    }

    memberType(node) {
        const objType = this.typeOf(node.object);
        if (objType === 'hljs') {
            const name = node.property.name;
            if (/_RE$/.test(name)) return 'string';
            if (['COMMENT', 'inherit', 'getLanguage', 'requireLanguage'].includes(name)) return 'function';

            return 'mode';
        }
        if (node.computed) {
            if (objType === 'modes') return 'mode';
            if (objType === 'strings' || objType === 'string') return 'string';

            return 'unknown';
        }
        const prop = node.property.name;
        if (prop === 'length') return 'number';
        if (objType === 'mode') {
            const t = MODE_PROPS[prop];
            if (t === 'keywords') return 'map';
            if (t === 'sublanguage') return 'unknown';

            return t || 'unknown';
        }
        if (objType === 'map') return 'string';

        return 'unknown';
    }

    callType(node) {
        const callee = node.callee;
        if (callee.type === 'MemberExpression') {
            const method = callee.property.name;
            const objType = this.typeOf(callee.object);
            if (objType === 'hljs') {
                if (method === 'COMMENT' || method === 'inherit') return 'mode';
                if (method === 'getLanguage' || method === 'requireLanguage') return 'lang';
            }
            if (method === 'rawDefinition') return 'mode';
            if (method === 'concat') {
                if (objType === 'string') return 'string';
                if (objType === 'modes' || objType === 'strings') return objType;
                for (const a of node.arguments) {
                    if (a.type === 'Literal' && a.value === 'self') return 'modes';
                    const t = this.typeOf(a);
                    if (t === 'mode' || t === 'modes') return 'modes';
                    if (t === 'string' || t === 'strings') return 'strings';
                }

                return 'modes';
            }
            if (method === 'split') return 'strings';
            if (method === 'map') return 'strings';
            if (method === 'slice') return objType === 'string' ? 'string' : objType;
            if (method === 'reverse') return objType;
            if (['join', 'trim', 'replace', 'toString'].includes(method)) return 'string';
        }
        if (callee.type === 'Identifier') {
            const fn = this.functionNode(callee.name);
            if (fn) return this.methodFor(fn, callee.name).returnType;
        }

        return 'unknown';
    }

    functionNode(name) {
        const found = this.lookup(name);
        if (!found || found.decl.kind === 'param') return null;

        return found.scope.info.functions.get(name) || null;
    }

    // ---- helper methods -------------------------------------------------

    /**
     * Describes the Java method for a function declared in the definition:
     * its parameters (with types inferred from the calls) and the local
     * variables it uses, which are passed as extra arguments.
     */
    methodFor(fn, name) {
        if (this.methodInfo.has(fn)) return this.methodInfo.get(fn);
        const found = this.lookup(name);
        const outer = found.scope;
        const info = {
            name: this.methodName(name),
            params: fn.params.map(function (p) { return { name: p.name, type: null }; }),
            captured: [],
            returnType: 'unknown',
            emitted: false,
        };
        this.methodInfo.set(fn, info);

        // parameter types from the arguments of the calls
        const calls = outer.info.calls.get(name) || [];
        this.withScope(outer, () => {
            info.params.forEach((p, i) => {
                for (const call of calls) {
                    if (call.arguments[i]) {
                        const t = this.typeOf(call.arguments[i]);
                        if (JAVA_TYPES[t] && t !== 'null') {
                            p.type = t;
                            break;
                        }
                    }
                }
            });
        });
        // parameters that are never passed: from their use in the function
        info.params.forEach((p) => {
            if (!p.type) p.type = this.paramTypeFromUse(fn, p.name);
        });

        // local variables of the enclosing scopes used by the function
        Array.from(freeVariables(fn)).forEach((v) => {
            if (v === name) return;
            if (v === 'hljs') {
                info.captured.push({ name: 'hljs', type: 'hljs' });
                return;
            }
            const f = this.lookup(v);
            if (!f || f.decl.kind === 'function') return;
            info.captured.push({ name: v, type: null });
        });

        this.withScope(outer, () => {
            info.captured.forEach((c) => {
                if (c.type === null) c.type = this.identifierType(c.name);
            });
        });

        // the return type
        this.withFunctionScope(fn, () => {
            const ret = fn.body.body.find(function (s) { return s.type === 'ReturnStatement'; });
            if (ret && ret.argument) {
                info.returnType = this.typeOf(ret.argument);
                if (info.returnType === 'empty') info.returnType = 'modes';
            } else {
                info.returnType = 'void';
            }
        }, info.params);

        return info;
    }

    methodName(name) {
        let n = javaName(name);
        if (RESERVED_NAMES.has(n)) n = n + '_';

        return n;
    }

    paramTypeFromUse(fn, name) {
        let type = null;
        const visit = (node, parent) => {
            if (!node || typeof node.type !== 'string' || type) return;
            if (node.type === 'LogicalExpression' && node.left.type === 'Identifier' && node.left.name === name) {
                if (node.right.type === 'ObjectExpression') type = 'mode';
            }
            if (node.type === 'Property' && node.value.type === 'Identifier' && node.value.name === name) {
                const t = MODE_PROPS[propKey(node)];
                if (t && JAVA_TYPES[t]) type = t;
            }
            for (const k of Object.keys(node)) {
                if (k === 'loc') continue;
                const child = node[k];
                if (Array.isArray(child)) child.forEach(function (c) { visit(c, node); });
                else if (child && typeof child.type === 'string') visit(child, node);
            }
        };
        visit(fn.body, null);
        if (!type) throw new TranspileError('Cannot determine the type of parameter ' + name, fn);

        return type;
    }

    // ---- statements -----------------------------------------------------

    /**
     * @param params optional [{name, type}] with the parameter types
     */
    withFunctionScope(fn, cb, params) {
        const names = fn.params.map(function (p) {
            if (p.type !== 'Identifier') throw new TranspileError('Unsupported parameter', p);

            return p.name;
        });
        const body = fn.body.type === 'BlockStatement' ? fn.body : { type: 'BlockStatement', body: [] };
        const scope = { info: analyse(body, names), fn: fn, id: this.scopeIds = (this.scopeIds || 0) + 1 };
        (params || []).forEach(function (p) {
            if (!scope.info.vars.has(p.name)) {
                scope.info.vars.set(p.name, { init: null, kind: 'param', declIndex: -1 });
            }
            scope.info.vars.get(p.name).type = p.type;
        });
        this.scopes.push(scope);
        try {
            return cb();
        } finally {
            this.scopes.pop();
        }
    }

    emitBlock(statements, indent) {
        const lines = [];
        let prevLine = null;

        statements.forEach((stmt) => {
            if (stmt.type === 'FunctionDeclaration' ||
                (stmt.type === 'VariableDeclaration' && stmt.declarations.every((d) => d.init && d.init.type === 'FunctionExpression'))) {
                // emitted as a method
                const decls = stmt.type === 'FunctionDeclaration' ? [{ id: stmt.id, init: stmt }] : stmt.declarations;
                decls.forEach((d) => this.emitMethod(d.init, d.id.name, stmt));
                return;
            }
            const before = lines.length;
            this.emitLeadingComments(lines, stmt, indent, prevLine);
            const leading = lines.length - before;
            const code = this.emitStatement(stmt, indent);
            if (code === null) return;
            // keep blank lines between statements that were separated in the source
            if (before > 0 && prevLine !== null && this.comments.lineOf(stmt.start) - prevLine > 1 &&
                lines[before - 1] !== '') {
                lines.splice(before, 0, '');
            }
            lines.push(code);
            this.emitTrailingComment(lines, stmt);
            prevLine = this.comments.lineOf(stmt.end);
            void leading;
        });

        return lines;
    }

    emitLeadingComments(lines, node, indent, prevLine) {
        const pad = INDENT.repeat(indent);
        this.comments.take(0, node.start).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine && lines.length) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, pad).forEach(function (l) { lines.push(l); });
            }
        });
    }

    emitTrailingComment(lines, node) {
        // comments on the same line right after the node
        const endLine = this.comments.lineOf(node.end);
        const cs = this.comments.comments.filter((c) => !this.comments.used.has(c) && c.start >= node.end &&
            this.comments.lineOf(c.start) === endLine && !/\S/.test(this.source.slice(node.end, c.start).replace(/[;,]/g, '')));
        cs.forEach((c) => {
            this.comments.used.add(c);
            lines[lines.length - 1] += ' ' + this.comments.format(c);
        });
    }

    emitStatement(stmt, indent) {
        const pad = INDENT.repeat(indent);
        switch (stmt.type) {
            case 'VariableDeclaration':
                return stmt.declarations.map((d) => {
                    let type = this.identifierType(d.id.name, d);
                    if (type === 'unknown' && !d.init && !this.scope.info.assignments.has(d.id.name)) {
                        // declared, but never used
                        return pad + 'Object ' + javaName(d.id.name) + ' = null;';
                    }
                    if (type === 'function') {
                        throw new TranspileError('Unsupported function variable', d);
                    }
                    const value = d.init ? this.emitValue(d.init, indent, type) : 'null';

                    return pad + javaType(type, d) + ' ' + javaName(d.id.name) + ' = ' + suffix(value, ';');
                }).join('\n');
            case 'ReturnStatement':
                return pad + 'return' + (stmt.argument ? ' ' + suffix(this.emitValue(stmt.argument, indent, this.returnContext), ';') : ';');
            case 'ExpressionStatement':
                return this.emitExpressionStatement(stmt.expression, indent);
            case 'EmptyStatement':
                return null;
            default:
                throw new TranspileError('Unsupported statement ' + stmt.type, stmt);
        }
    }

    emitExpressionStatement(expr, indent) {
        const pad = INDENT.repeat(indent);
        if (expr.type === 'AssignmentExpression') {
            const left = expr.left;
            if (left.type === 'MemberExpression' && !left.computed && this.typeOf(left.object) === 'map') {
                // map.key = value, map.key += value
                const target = this.emitValue(left.object, indent, null);
                const key = javaString(left.property.name);
                let value = this.emitValue(expr.right, indent, 'string');
                if (expr.operator === '+=') {
                    value = target + '.get(' + key + ') + ' + value;
                } else if (expr.operator !== '=') {
                    throw new TranspileError('Unsupported assignment operator ' + expr.operator, expr);
                }

                return pad + target + '.put(' + key + ', ' + suffix(value, ');');
            }
            const leftType = this.typeOf(left);
            const target = this.emitLValue(left, indent);
            if (expr.operator === '=') {
                return pad + target + ' = ' + suffix(this.emitValue(expr.right, indent, leftType), ';');
            }
            if (expr.operator === '+=' && (leftType === 'string' || leftType === 'number')) {
                return pad + target + ' += ' + suffix(this.emitValue(expr.right, indent, leftType), ';');
            }
            throw new TranspileError('Unsupported assignment operator ' + expr.operator, expr);
        }
        if (expr.type === 'CallExpression' && expr.callee.type === 'MemberExpression') {
            const method = expr.callee.property.name;
            const obj = expr.callee.object;
            if (method === 'push' || method === 'unshift') {
                const objType = this.typeOf(obj);
                const elementType = objType === 'strings' ? 'string' : 'mode';
                const target = this.emitValue(obj, indent, objType);
                const args = expr.arguments.map((a) => this.emitValue(a, indent + 1, elementType));
                if (method === 'unshift') {
                    if (args.length !== 1) throw new TranspileError('unshift needs one argument', expr);

                    return pad + target + '.add(0, ' + suffix(args[0], ');');
                }

                return args.map((a) => pad + target + '.add(' + suffix(a, ');')).join('\n');
            }
            if (method === 'forEach') {
                const fn = expr.arguments[0];
                if (fn.type !== 'FunctionExpression' || fn.params.length !== 1) {
                    throw new TranspileError('Unsupported forEach callback', fn);
                }
                const objType = this.typeOf(obj);
                const elementType = objType === 'strings' ? 'string' : 'mode';
                const param = fn.params[0].name;

                return this.withFunctionScope(fn, () => {
                    const body = this.emitBlock(fn.body.body, indent + 1);

                    return pad + 'for (' + javaType(elementType) + ' ' + javaName(param) + ' : ' +
                        this.emitValue(obj, indent, objType) + ') {\n' + body.join('\n') + '\n' + pad + '}';
                }, [{ name: param, type: elementType }]);
            }
            if (obj.type === 'Identifier' && obj.name === 'Object' && method === 'assign') {
                const target = this.emitValue(expr.arguments[0], indent, 'mode');
                const parts = [];
                expr.arguments.slice(1).forEach((src) => {
                    if (src.type !== 'ObjectExpression') throw new TranspileError('Object.assign needs object literals', src);
                    parts.push(src);
                });
                const props = [].concat(...parts.map((p) => p.properties));
                const chain = this.emitModeProperties({ properties: props, start: parts[0].start, end: parts[parts.length - 1].end, loc: parts[0].loc }, indent);

                return pad + suffix(target + chain, ';');
            }
        }
        if (expr.type === 'Literal') {
            // a no-op expression in the source
            return null;
        }
        throw new TranspileError('Unsupported expression statement ' + expr.type, expr);
    }

    emitLValue(node, indent) {
        if (node.type === 'Identifier') return javaName(node.name);
        if (node.type === 'MemberExpression') {
            if (!node.computed && this.typeOf(node.object) === 'mode' && MODE_PROPS[node.property.name] === undefined) {
                throw new TranspileError('Unsupported assignment to ' + node.property.name, node);
            }

            return this.emitMember(node, indent, true);
        }
        throw new TranspileError('Unsupported assignment target', node);
    }

    // ---- functions ------------------------------------------------------

    emitMethod(fn, name, stmt) {
        const info = this.methodFor(fn, name);
        if (info.emitted) return;
        info.emitted = true;
        const leading = [];
        this.comments.take(0, stmt.start).forEach((c) => {
            this.comments.lines(c, INDENT).forEach(function (l) { leading.push(l); });
        });
        const params = info.params.concat(info.captured).map(function (p) {
            return (p.type === 'hljs' ? 'Hljs' : javaType(p.type, fn)) + ' ' + javaName(p.name);
        });
        const returnType = info.returnType === 'void' ? 'void' : javaType(info.returnType, fn);
        const lines = this.withFunctionScope(fn, () => {
            const saved = this.returnContext;
            this.returnContext = info.returnType;
            try {
                return this.emitBlock(fn.body.body, 2);
            } finally {
                this.returnContext = saved;
            }
        }, info.params.concat(info.captured.filter(function (c) { return c.type !== 'hljs'; })));
        this.methods.push(leading.concat([
            INDENT + 'private static ' + returnType + ' ' + info.name + '(' + params.join(', ') + ') {',
        ]).concat(lines).concat([INDENT + '}']).join('\n'));
    }

    emitCallOfMethod(node, indent) {
        const fn = this.functionNode(node.callee.name);
        if (!fn) throw new TranspileError('Unknown function ' + node.callee.name, node);
        const info = this.methodFor(fn, node.callee.name);
        const args = info.params.map((p, i) => {
            const a = node.arguments[i];
            if (!a) return 'null';

            return this.emitValue(a, indent + 1, p.type);
        });
        info.captured.forEach(function (c) { args.push(javaName(c.name)); });

        return call(info.name, args, indent);
    }

    /** A callback, as a Java lambda. */
    emitLambda(fn, indent, paramType) {
        if (fn.type !== 'FunctionExpression' || fn.params.length !== 1) {
            throw new TranspileError('Unsupported callback', fn);
        }
        const param = fn.params[0].name;
        const body = fn.body.body;
        if (body.length !== 1 || body[0].type !== 'ReturnStatement') {
            throw new TranspileError('Only callbacks that return a value are supported', fn);
        }

        return this.withFunctionScope(fn, () => {
            return javaName(param) + ' -> ' + this.emitValue(body[0].argument, indent, null);
        }, [{ name: param, type: paramType }]);
    }

    // ---- expressions ----------------------------------------------------

    /**
     * @param expected the expected type, determines how literals are emitted
     */
    emitValue(node, indent, expected) {
        switch (node.type) {
            case 'Literal':
                if (node.regex) {
                    if (node.regex.flags && node.regex.flags !== 'g') {
                        throw new TranspileError('Unsupported regex flags ' + node.regex.flags, node);
                    }

                    return javaString(node.regex.pattern);
                }
                if (typeof node.value === 'string') {
                    if (node.value === 'self' && expected === 'mode') return 'Mode.SELF';

                    return javaString(node.value);
                }
                if (node.value === null) return 'null';
                // `false` where a regex is expected (like `hljs.COMMENT(begin, false)`) means: no regex
                if (node.value === false && expected === 'string') return 'null';
                if (typeof node.value === 'number' && !Number.isInteger(node.value)) {
                    throw new TranspileError('Unsupported number ' + node.value, node);
                }

                return String(node.value);
            case 'Identifier':
                if (node.name === 'undefined') return 'null';
                if (!this.lookup(node.name) && node.name !== 'hljs') {
                    throw new TranspileError('Unknown variable ' + node.name, node);
                }

                return javaName(node.name);
            case 'ObjectExpression':
                return this.emitObject(node, indent, expected);
            case 'ArrayExpression':
                return this.emitArray(node, indent, expected);
            case 'BinaryExpression':
                return this.emitBinary(node, indent);
            case 'LogicalExpression':
                if (node.operator === '||') {
                    this.use('Js');
                    const t = expected || this.typeOf(node);

                    return call('or', [this.emitValue(node.left, indent, t), this.emitValue(node.right, indent, t)], indent);
                }
                throw new TranspileError('Unsupported logical operator ' + node.operator, node);
            case 'MemberExpression':
                return this.emitMember(node, indent, false);
            case 'CallExpression':
                return this.emitCall(node, indent, expected);
            default:
                throw new TranspileError('Unsupported expression ' + node.type, node);
        }
    }

    sameLine(node) {
        return node.loc.start.line === node.loc.end.line;
    }

    /**
     * Renders items between open and close, either on one line or one per
     * line, preserving comments.
     *
     * @param items [{node, render(indent) => code}]
     */
    emitList(node, items, open, close, indent, sep) {
        sep = sep === undefined ? ', ' : sep;
        const pad = INDENT.repeat(indent);
        const inner = INDENT.repeat(indent + 1);
        const rendered = items.map((item) => ({ item: item, code: item.render(indent + 1) }));

        const hasComments = this.comments.hasUnused(node.start, node.end);
        const oneLine = open + rendered.map(function (r) { return r.code; }).join(sep) + close;
        if (!hasComments && (this.sameLine(node) || items.length <= 1) && !oneLine.includes('\n') &&
            width(pad + oneLine) < MAX_LINE) {
            return oneLine;
        }
        if (items.length === 0) {
            return open + close;
        }

        const lines = [open];
        let prevLine = null;
        rendered.forEach((r, i) => {
            const start = r.item.node.start;
            this.comments.take(node.start, start).forEach((c) => {
                if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                    lines[lines.length - 1] += ' ' + this.comments.format(c);
                } else {
                    this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
                }
            });
            lines.push(inner + (i < rendered.length - 1 ? suffix(r.code, sep.trimEnd()) : r.code));
            prevLine = this.comments.lineOf(r.item.node.end);
        });
        this.comments.take(node.start, node.end).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
            }
        });

        return suffix(lines.join('\n'), close);
    }

    emitObject(node, indent, expected) {
        if (expected === 'map' || expected === 'objmap' || expected === 'keywords') {
            return this.emitMap(node, indent, expected === 'keywords' ? this.mapType(node) : expected);
        }
        if (expected && expected !== 'mode' && expected !== 'unknown') {
            throw new TranspileError('Object literal where a ' + expected + ' is expected', node);
        }

        return 'new Mode()' + this.emitModeProperties(node, indent);
    }

    emitMap(node, indent, type) {
        this.use('Js');
        const valueType = type === 'map' ? 'string' : null;
        const items = node.properties.map((p) => {
            this.checkProperty(p);

            return {
                node: p,
                render: (ind) => javaString(propKey(p)) + ', ' + this.emitValue(p.value, ind, valueType),
            };
        });

        return this.emitList(node, items, type === 'map' ? 'map(' : 'objectMap(', ')', indent);
    }

    checkProperty(p) {
        if (p.type !== 'Property' || p.kind !== 'init' || p.method || p.shorthand) {
            throw new TranspileError('Unsupported object property', p);
        }
    }

    /** The properties of a mode literal, as a chain of setter calls. */
    emitModeProperties(node, indent) {
        const items = node.properties.map((p) => {
            this.checkProperty(p);
            const key = propKey(p);

            return {
                node: p,
                render: (ind) => this.emitModeProperty(key, p.value, ind),
            };
        });
        if (!items.length) return '';
        // `.prop(...)` calls on one line, or one per line
        const pad = INDENT.repeat(indent);
        const rendered = items.map((item) => ({ item: item, code: item.render(indent + 1) }));
        const hasComments = this.comments.hasUnused(node.start, node.end);
        const oneLine = rendered.map(function (r) { return r.code; }).join('');
        if (!hasComments && this.sameLine(node) && !oneLine.includes('\n') && width(pad + 'new Mode()' + oneLine) < MAX_LINE - 10) {
            return oneLine;
        }

        const inner = INDENT.repeat(indent + 1);
        const lines = [''];
        let prevLine = null;
        rendered.forEach((r) => {
            this.comments.take(node.start, r.item.node.start).forEach((c) => {
                if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                    lines[lines.length - 1] += ' ' + this.comments.format(c);
                } else {
                    this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
                }
            });
            lines.push(inner + r.code);
            prevLine = this.comments.lineOf(r.item.node.end);
        });
        this.comments.take(node.start, node.end).forEach((c) => {
            if (prevLine !== null && this.comments.lineOf(c.start) === prevLine) {
                lines[lines.length - 1] += ' ' + this.comments.format(c);
            } else {
                this.comments.lines(c, inner).forEach(function (l) { lines.push(l); });
            }
        });

        return lines.join('\n');
    }

    emitModeProperty(key, value, indent) {
        const type = MODE_PROPS[key];
        const valueType = this.typeOf(value);
        if (valueType === 'null') {
            return '.set(' + javaString(key) + ', null)';
        }
        if (!type) {
            // not a mode property: kept in `extra`
            let expected = null;
            if (value.type === 'ObjectExpression') expected = this.mapType(value) === 'map' ? 'map' : 'objmap';

            return call('.set', [javaString(key), this.emitValue(value, indent, expected)], indent);
        }
        if (type === 'keywords') {
            if (value.type === 'ObjectExpression') return call('.keywords', [this.emitValue(value, indent, 'keywords')], indent);
            if (valueType === 'string' || valueType === 'map') return call('.keywords', [this.emitValue(value, indent, valueType)], indent);
            if (value.type === 'MemberExpression' && !value.computed && value.property.name === 'keywords') {
                return call('.set', ['"keywords"', this.emitMember(value, indent, true)], indent);
            }
            throw new TranspileError('Cannot determine the type of keywords', value);
        }
        if (type === 'sublanguage') {
            if (valueType === 'string') return call('.subLanguage', [this.emitValue(value, indent, 'string')], indent);
            if (value.type === 'ArrayExpression' || valueType === 'strings') {
                return call('.subLanguage', [this.emitValue(value, indent, 'strings')], indent);
            }
            throw new TranspileError('Cannot determine the type of subLanguage', value);
        }
        if ((type === 'modes' || type === 'strings') && value.type === 'ArrayExpression') {
            // varargs setter
            const elementType = type === 'modes' ? 'mode' : 'string';
            const items = value.elements.map((el) => {
                if (el === null || el.type === 'SpreadElement') throw new TranspileError('Unsupported array element', value);

                return { node: el, render: (ind) => this.emitValue(el, ind, elementType) };
            });
            if (items.length === 0) {
                return '.' + key + '(' + (type === 'modes' ? 'new Mode[0]' : 'new String[0]') + ')';
            }

            return '.' + key + this.emitList(value, items, '(', ')', indent);
        }
        if (type === 'number' && valueType !== 'number' && valueType !== 'unknown') {
            throw new TranspileError('Expected a number for ' + key, value);
        }

        return call('.' + key, [this.emitValue(value, indent, type)], indent);
    }

    emitArray(node, indent, expected) {
        let type = expected;
        if (type !== 'modes' && type !== 'strings') {
            type = this.typeOf(node);
            if (type === 'empty' || type === 'unknown') type = 'modes';
        }
        const elementType = type === 'modes' ? 'mode' : 'string';
        this.use('Js');
        const items = node.elements.map((el) => {
            if (el === null) throw new TranspileError('Array holes are not supported', node);
            if (el.type === 'SpreadElement') throw new TranspileError('Spread is not supported', el);

            return { node: el, render: (ind) => this.emitValue(el, ind, elementType) };
        });
        if (items.length === 0) {
            return 'Js.<' + (type === 'modes' ? 'Mode' : 'String') + '>list()';
        }

        return this.emitList(node, items, 'list(', ')', indent);
    }

    emitBinary(node, indent) {
        if (node.operator !== '+') {
            throw new TranspileError('Unsupported operator ' + node.operator, node);
        }
        const type = this.typeOf(node);
        if (type === 'number') {
            return this.emitValue(node.left, indent, 'number') + ' + ' + this.emitValue(node.right, indent, 'number');
        }
        if (type !== 'string') {
            throw new TranspileError('Cannot determine type of + expression', node);
        }
        // flatten string concatenation chains, keep line breaks of the source
        const operands = [];
        const flatten = (n) => {
            if (n.type === 'BinaryExpression' && n.operator === '+' && this.typeOf(n.left) === 'string') {
                flatten(n.left);
                flatten(n.right);
            } else {
                operands.push(n);
            }
        };
        flatten(node);
        const constant = operands.reduce(function (sum, op) {
            return sum + (op.type === 'Literal' && typeof op.value === 'string' ? op.value.length : 0);
        }, 0);
        const cont = INDENT.repeat(indent + 2);
        const codes = operands.map((op) => {
            const code = this.emitValue(op, indent + 2, null);

            return op.type === 'BinaryExpression' ? '(' + suffix(code, ')') : code;
        });
        if (constant > MAX_CONSTANT) {
            // javac would fold the constants into a string that is too long for the class file
            return 'String.join(""' + codes.map(function (c, i) {
                return (i > 0 && operands[i].loc.start.line !== operands[i - 1].loc.end.line ? ',\n' + cont : ', ') + c;
            }).join('') + ')';
        }
        let out = '';
        codes.forEach((code, i) => {
            if (i === 0) {
                out = code;
            } else if (operands[i].loc.start.line !== operands[i - 1].loc.end.line || endsWithComment(out)) {
                out += '\n' + cont + '+ ' + code;
            } else {
                out += ' + ' + code;
            }
        });

        return out;
    }

    emitMember(node, indent, raw) {
        const obj = node.object;
        const objType = this.typeOf(obj);
        if (objType === 'hljs') {
            return 'hljs.' + node.property.name;
        }
        const target = obj.type === 'Identifier' ? javaName(obj.name) : this.emitValue(obj, indent, null);
        if (node.computed) {
            if (objType === 'modes' || objType === 'strings') {
                return call(target + '.get', [this.emitValue(node.property, indent, 'number')], indent);
            }
            throw new TranspileError('Unsupported computed member access on ' + objType, node);
        }
        const prop = node.property.name;
        if (prop === 'length') {
            if (objType === 'string') return target + '.length()';
            if (objType === 'modes' || objType === 'strings') return target + '.size()';
            throw new TranspileError('Unsupported length of ' + objType, node);
        }
        if (objType === 'map') {
            return target + '.get(' + javaString(prop) + ')';
        }
        if (objType === 'mode') {
            if (MODE_PROPS[prop] === undefined) {
                throw new TranspileError('Unsupported mode property ' + prop, node);
            }
            if (MODE_PROPS[prop] === 'keywords' && !raw) {
                this.use('Js');

                return 'keywordMap(' + target + '.keywords)';
            }

            return target + '.' + prop;
        }
        throw new TranspileError('Unsupported member access on ' + objType, node);
    }

    emitCall(node, indent, expected) {
        const callee = node.callee;
        const args = node.arguments;

        if (callee.type === 'Identifier') {
            return this.emitCallOfMethod(node, indent);
        }
        if (callee.type !== 'MemberExpression' || callee.computed) {
            throw new TranspileError('Unsupported call', node);
        }

        const method = callee.property.name;
        const obj = callee.object;
        const objType = this.typeOf(obj);

        if (objType === 'hljs') {
            if (method === 'inherit') {
                return call('hljs.inherit', args.map((a) => this.emitValue(a, indent + 1, 'mode')), indent);
            }
            if (method === 'COMMENT') {
                const types = ['string', 'string', 'mode'];

                const codes = args.map((a, i) => this.emitValue(a, indent + 1, types[i]));
                while (codes.length < 2) codes.push('null');

                return call('hljs.COMMENT', codes, indent);
            }
            if (method === 'getLanguage' || method === 'requireLanguage') {
                return call('hljs.' + method, args.map((a) => this.emitValue(a, indent, 'string')), indent);
            }
            throw new TranspileError('Unsupported hljs method ' + method, node);
        }

        const self = () => this.emitValue(obj, indent, objType);

        switch (method) {
            case 'rawDefinition':
                return self() + '.rawDefinition()';
            case 'concat': {
                if (objType === 'string') {
                    return [self()].concat(args.map((a) => this.emitValue(a, indent, null))).join(' + ');
                }
                const type = this.typeOf(node);
                const elementType = type === 'modes' ? 'mode' : 'string';
                const parts = args.map((a) => {
                    if (a.type === 'Literal' && a.value === 'self' && type === 'modes') return 'Mode.SELF';
                    const t = this.typeOf(a);
                    if (t === type || t === 'empty') return this.emitValue(a, indent + 1, type);
                    if (t === elementType || (elementType === 'mode' && a.type === 'ObjectExpression')) {
                        return this.emitValue(a, indent + 1, elementType);
                    }
                    throw new TranspileError('Cannot determine type of concat argument', a);
                });
                this.use('Js');

                return call('Js.<' + (type === 'modes' ? 'Mode' : 'String') + '>concat',
                    [this.emitValue(obj, indent + 1, type)].concat(parts), indent);
            }
            case 'join':
                this.use('Js');

                return call('join', [self(), this.emitValue(args[0], indent, 'string')], indent);
            case 'split': {
                const sep = args[0];
                if (sep.type === 'Literal' && typeof sep.value === 'string') {
                    this.use('Js');

                    return call('split', [self(), javaString(sep.value)], indent);
                }
                throw new TranspileError('Unsupported split separator', sep);
            }
            case 'trim':
                return self() + '.trim()';
            case 'toString':
                if (objType !== 'string') throw new TranspileError('Unsupported toString', node);

                return self();
            case 'reverse':
                this.use('Js');

                return call('reverse', [self()], indent);
            case 'slice': {
                if (objType === 'string') {
                    throw new TranspileError('Unsupported string slice', node);
                }
                if (args.length > 1) throw new TranspileError('slice with end is not supported', node);
                this.use('Js');

                return call('slice', [self(), args.length ? this.emitValue(args[0], indent, 'number') : '0'], indent);
            }
            case 'map': {
                if (objType !== 'strings') throw new TranspileError('map is only supported on lists of strings', node);
                this.use('Js');

                return call('map', [self(), this.emitLambda(args[0], indent, 'string')], indent);
            }
            case 'replace': {
                const pattern = args[0];
                const replacement = this.emitValue(args[1], indent, 'string');
                this.use('Js');
                if (pattern.type === 'Literal' && pattern.regex) {
                    if (pattern.regex.flags.replace('g', '')) {
                        throw new TranspileError('Unsupported regex flags ' + pattern.regex.flags, pattern);
                    }

                    return call('replaceRegex', [self(), javaString(pattern.regex.pattern), replacement,
                        String(pattern.regex.flags.includes('g'))], indent);
                }
                if (pattern.type === 'Literal' && typeof pattern.value === 'string') {
                    return call('replace', [self(), javaString(pattern.value), replacement], indent);
                }
                throw new TranspileError('Unsupported replace pattern', pattern);
            }
            default:
                throw new TranspileError('Unsupported method ' + method, node);
        }
    }
}

/** Appends a suffix to the last line of code, before comments. */
function suffix(code, s) {
    const lines = code.split('\n');
    let i = lines.length - 1;
    while (i > 0 && lines[i].startsWith(COMMENT_LINE)) i--;
    const line = lines[i];
    const mark = line.indexOf(COMMENT_MARK);
    lines[i] = mark < 0 ? line + s : line.slice(0, mark).trimEnd() + s + ' ' + line.slice(mark);

    return lines.join('\n');
}

/** Whether code ends with a comment, so nothing may follow on the same line. */
function endsWithComment(code) {
    const last = code.slice(code.lastIndexOf('\n') + 1);

    return last.includes(COMMENT_MARK) || last.startsWith(COMMENT_LINE);
}

/** Joins arguments with commas, moving to a new line after a comment. */
function joinArgs(codes, indent) {
    let out = '';
    codes.forEach(function (c, i) {
        if (i === 0) {
            out = c;
        } else if (endsWithComment(out)) {
            out = suffix(out, ',') + '\n' + INDENT.repeat(indent + 1) + c;
        } else {
            out += ', ' + c;
        }
    });

    return out;
}

/** A call: name(args...). */
function call(name, codes, indent) {
    return name + '(' + suffix(joinArgs(codes, indent), ')');
}

function width(line) {
    return line.replace(/\t/g, ' '.repeat(TAB_WIDTH)).length;
}

// ---------------------------------------------------------------------------

function headerDocBlock(comment) {
    const lines = comment.value.replace(/^\*?\s*\n/, '').replace(/\s+$/, '').split('\n');

    return ['/**'].concat(lines.map(function (l) {
        const t = safeComment(l.replace(/^\s*\*?\s?/, '').replace(/\*\//g, '*\\/').trimEnd());

        return t ? ' * ' + t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/@/g, '&#64;') : ' *';
    })).concat([' */']).join('\n');
}

/**
 * @param {string} source       JS source of the language definition
 * @param {string} languageId   e.g. "c-like"
 * @param {object} options      {hljsVersion}
 * @returns {string} Java source
 */
function transpile(source, languageId, options) {
    options = options || {};
    const comments = [];
    const ast = acorn.parse(source, {
        ecmaVersion: 2020,
        sourceType: 'module',
        locations: true,
        onComment: comments,
    });

    const exported = ast.body.find(function (n) { return n.type === 'ExportDefaultDeclaration'; });
    if (!exported || exported.declaration.type !== 'FunctionDeclaration') {
        throw new TranspileError('Expected `export default function(hljs)`', ast);
    }
    const fn = exported.declaration;
    if (fn.params.length !== 1 || fn.params[0].name !== 'hljs') {
        throw new TranspileError('Expected a single `hljs` parameter', fn);
    }

    const cm = new Comments(comments, source);
    const header = comments.find(function (c) { return c.type === 'Block' && c.end <= exported.start; });
    // comments before the export (other than the header) are dropped
    comments.filter(function (c) { return c.end <= exported.start; }).forEach(function (c) { cm.used.add(c); });

    const emitter = new Emitter(source, cm, languageId);
    const body = emitter.withFunctionScope(fn, function () {
        emitter.returnContext = 'mode';

        return emitter.emitBlock(fn.body.body, 2);
    }, [{ name: 'hljs', type: 'hljs' }]);
    // comments after the last statement
    const rest = [];
    cm.take(fn.body.start, fn.body.end).forEach(function (c) {
        cm.lines(c, INDENT.repeat(2)).forEach(function (l) { rest.push(l); });
    });

    const code = body.join('\n') + (rest.length ? '\n' + rest.join('\n') : '') + '\n' + emitter.methods.join('\n');
    const imports = [];
    if (emitter.imports.has('Js')) {
        imports.push('import static org.scrivo.highlight.Js.*;', '');
    }
    const javaUtil = [];
    if (/\bList</.test(code)) javaUtil.push('import java.util.List;');
    if (/\bMap</.test(code)) javaUtil.push('import java.util.Map;');
    if (javaUtil.length) imports.push(...javaUtil, '');
    imports.push('import org.scrivo.highlight.Hljs;');
    if (/\bJs\./.test(code)) imports.push('import org.scrivo.highlight.Js;');
    if (/\bLanguage\b/.test(code.replace(/LanguageDefinition/g, ''))) imports.push('import org.scrivo.highlight.Language;');
    imports.push('import org.scrivo.highlight.LanguageDefinition;');
    imports.push('import org.scrivo.highlight.Mode;');

    const cls = className(languageId);
    const out = [
        '/* This file is generated by tools/transpiler from highlight.js' +
            (options.hljsVersion ? ' ' + options.hljsVersion : '') + ' src/languages/' + languageId + '.js. */',
        '',
        'package org.scrivo.highlight.languages;',
        '',
    ].concat(imports).concat([
        '',
        header ? headerDocBlock(header) : null,
        'public final class ' + cls + ' implements LanguageDefinition {',
        '',
        INDENT + 'public static final String ID = ' + javaString(languageId) + ';',
        '',
        INDENT + '@Override',
        INDENT + 'public Mode define(Hljs hljs) {',
        body.join('\n') + (rest.length ? '\n' + rest.join('\n') : ''),
        INDENT + '}',
    ]).concat(emitter.methods.length ? ['', emitter.methods.join('\n\n')] : []).concat([
        '}',
        '',
    ]).filter(function (l) { return l !== null; });

    return out.join('\n').split(COMMENT_MARK).join('').split(COMMENT_LINE).join('');
}

module.exports = { transpile: transpile, className: className, TranspileError: TranspileError };

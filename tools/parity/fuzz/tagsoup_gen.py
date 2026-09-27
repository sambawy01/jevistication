import random, json, sys
# Round-11 review (r11-work/fuzz.py): tag-soup documents whose last link Chrome renders or not.
# Usage: tagsoup_gen.py SEED N > fzSEED.jsonl; the hosts come from Chrome (tagsoup-pinned.json).
random.seed(int(sys.argv[1])); N=int(sys.argv[2])
V=['<b>','</b>','<i>','</i>','<nobr>','</nobr>','<a>','</a>','<table>','</table>','<tr>','</tr>','<td>','</td>','<th>','<caption>','</caption>','<tbody>','</tbody>','<colgroup>','<col>',
 '<select>','</select>','<option>','</option>','<optgroup>','<textarea>','</textarea>','<template>','</template>','<template shadowrootmode=open>','<div>','</div>',
 '<svg>','</svg>','<math>','</math>','<mtext>','<mi>','<foreignObject>','</foreignObject>','<desc>','<p>','</p>','<button>','</button>','<form>','</form>','<ruby>','<rt>','<rp>','</ruby>',
 '<marquee>','</marquee>','<frameset>','<noscript>','</noscript>','<style>','</style>','<xmp>','</xmp>','<h1>','</h2>','<li>','<ul>','</ul>','<dd>','<object>','</object>','<applet>','</applet>',
 '<input>','<keygen>','<hr>','<br>','</br>','<image>','<isindex>','<plaintext>','<title>','</title>','<font color=red>','</font>','<span>','</span>','<center>','<pre>','<listing>','<body>','</body>','</html>','<head>','<frame>','<noframes>','</noframes>','<iframe>','</iframe>','<noembed>','</noembed>','<annotation-xml encoding=text/html>','<!--','-->','Sign in ']
out=[]
for d in range(N):
    k=0; parts=[]
    for _ in range(random.randint(3,12)):
        if random.random()<0.3:
            parts.append(f'<a href="https://h{k}.example/">Sign in to PayPal {k}</a>' if random.random()<0.6 else f'<a href="https://h{k}.example/">t{k}'); k+=1
        else: parts.append(random.choice(V))
    parts.append(f'<a href="https://h{k}.example/">Sign in to PayPal {k}</a>')
    out.append({"label":f"f{sys.argv[1]}-{d}","html":"".join(parts)})
print("\n".join(json.dumps(o) for o in out))

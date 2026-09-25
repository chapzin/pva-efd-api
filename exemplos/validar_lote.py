#!/usr/bin/env python3
"""Valida vários arquivos EFD no serviço e grava um JSON por arquivo + um resumo CSV.

Uso:
  python3 validar_lote.py 'pasta/*.txt' [pasta_resultados]

Só usa a biblioteca padrão do Python. Variável PVA_URL muda o endereço do serviço
(padrão http://127.0.0.1:8095).
"""
import collections
import csv
import glob
import json
import os
import sys
import urllib.request

URL = os.environ.get('PVA_URL', 'http://127.0.0.1:8095').rstrip('/')


def validar(caminho):
    with open(caminho, 'rb') as f:
        req = urllib.request.Request(f'{URL}/validar', data=f.read(), headers={'Content-Type': 'text/plain'})
    # Arquivos grandes levam minutos, e o serviço valida um por vez.
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.load(r)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    arquivos = sorted(glob.glob(sys.argv[1]))
    saida = sys.argv[2] if len(sys.argv) > 2 else 'resultados'
    os.makedirs(saida, exist_ok=True)
    resumo = []
    for i, caminho in enumerate(arquivos, 1):
        nome = os.path.basename(caminho)
        try:
            r = validar(caminho)
        except Exception as e:
            print(f'[{i}/{len(arquivos)}] {nome}: FALHA {e}', flush=True)
            resumo.append({'arquivo': nome, 'estado': 'FALHA', 'valido': False, 'erros': '', 'advertencias': '',
                           'principais': str(e)})
            continue
        with open(os.path.join(saida, nome + '.json'), 'w', encoding='utf-8') as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
        erros = [e for e in r.get('erros', []) if e.get('tipo') == 'E']
        advert = [e for e in r.get('erros', []) if e.get('tipo') == 'A']
        top = collections.Counter(f"{e.get('registro')}:{e.get('codigo')}" for e in erros).most_common(3)
        principais = '; '.join(f'{k} x{n}' for k, n in top)
        print(f"[{i}/{len(arquivos)}] {nome}: {r.get('estado')} ({len(erros)} erros) {principais}", flush=True)
        resumo.append({'arquivo': nome, 'estado': r.get('estado'), 'valido': r.get('valido'), 'erros': len(erros),
                       'advertencias': len(advert), 'principais': principais or r.get('falha', '')})
    with open(os.path.join(saida, 'resumo.csv'), 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['arquivo', 'estado', 'valido', 'erros', 'advertencias', 'principais'],
                           delimiter=';')
        w.writeheader()
        w.writerows(resumo)
    aprovados = sum(1 for r in resumo if r['valido'])
    print(f'\n{aprovados}/{len(resumo)} aprovados. Detalhes em {saida}/')


if __name__ == '__main__':
    main()

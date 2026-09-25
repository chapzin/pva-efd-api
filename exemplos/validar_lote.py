#!/usr/bin/env python3
"""Valida vários arquivos EFD no serviço e monta o painel da carteira.

Uso:
  python3 validar_lote.py 'pasta/*.txt' [pasta_resultados] [--analisar]

Grava na pasta de resultados:
  <arquivo>.json           resposta completa do serviço
  resumo.csv               um arquivo por linha: estado, erros, principais mensagens
  erros-por-mensagem.csv   cada mensagem do PVA, em quantos arquivos e quantas vezes aparece
Com --analisar (usa POST /analisar em vez de /validar):
  verificacoes.csv         achados de malha por arquivo (crédito de uso e consumo, etc.)
  saldo-credor.csv         meses em que o saldo credor transportado não bate com o
                           saldo credor anterior do mês seguinte (mesmo CNPJ/IE);
                           tipo "quebra" a partir de R$ 1,00, "arredondamento" abaixo

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
from decimal import Decimal

URL = os.environ.get('PVA_URL', 'http://127.0.0.1:8095').rstrip('/')
# Diferença de centavos entre meses é arredondamento do ERP; quebra de verdade fica acima disso.
TOLERANCIA = Decimal('1.00')


def enviar(caminho, rota):
    with open(caminho, 'rb') as f:
        req = urllib.request.Request(f'{URL}/{rota}', data=f.read(), headers={'Content-Type': 'text/plain'})
    # Arquivos grandes levam minutos, e o serviço valida um por vez.
    with urllib.request.urlopen(req, timeout=1800) as r:
        return json.load(r)


def br(v):
    # Excel em português lê "36.0" como texto; vírgula decimal abre como número.
    return str(v).replace('.', ',')


def gravar_csv(caminho, campos, linhas):
    with open(caminho, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=campos, delimiter=';')
        w.writeheader()
        w.writerows(linhas)


def cadeia_saldo_credor(periodos):
    """periodos: (arquivo, resumo) de /analisar. Compara mês a mês por contribuinte."""
    por_contrib = collections.defaultdict(list)
    for nome, res in periodos:
        c, p, ap = res.get('contribuinte') or {}, res.get('periodo') or {}, res.get('apuracaoIcms')
        if not ap or not p.get('inicio'):
            continue
        chave = (c.get('cnpj') or c.get('cpf'), c.get('ie'))
        por_contrib[chave].append((p['inicio'], nome, ap))
    saida = []
    for (doc, ie), meses in por_contrib.items():
        meses.sort()
        for (ini_a, arq_a, ap_a), (ini_b, arq_b, ap_b) in zip(meses, meses[1:]):
            if ini_a == ini_b:
                continue
            transportado = Decimal(str(ap_a.get('VL_SLD_CREDOR_TRANSPORTAR', 0)))
            anterior = Decimal(str(ap_b.get('VL_SLD_CREDOR_ANT', 0)))
            if transportado != anterior:
                tipo = 'arredondamento' if abs(anterior - transportado) < TOLERANCIA else 'quebra'
                saida.append({'tipo': tipo, 'contribuinte': doc, 'ie': ie, 'mes': ini_a[:7], 'arquivo': arq_a,
                              'saldo_transportado': br(transportado), 'mes_seguinte': ini_b[:7], 'arquivo_seguinte': arq_b,
                              'saldo_credor_anterior': br(anterior), 'diferenca': br(anterior - transportado)})
    return saida


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    analisar = '--analisar' in sys.argv
    if not args:
        sys.exit(__doc__)
    arquivos = sorted(glob.glob(args[0]))
    saida = args[1] if len(args) > 1 else 'resultados'
    os.makedirs(saida, exist_ok=True)
    resumo, achados, periodos = [], [], []
    por_msg = collections.defaultdict(lambda: {'arquivos': set(), 'ocorrencias': 0, 'tipo': '', 'descricao': ''})
    for i, caminho in enumerate(arquivos, 1):
        nome = os.path.basename(caminho)
        try:
            r = enviar(caminho, 'analisar' if analisar else 'validar')
        except Exception as e:
            print(f'[{i}/{len(arquivos)}] {nome}: FALHA {e}', flush=True)
            resumo.append({'arquivo': nome, 'estado': 'FALHA', 'valido': False, 'principais': str(e)})
            continue
        with open(os.path.join(saida, nome + '.json'), 'w', encoding='utf-8') as f:
            json.dump(r, f, ensure_ascii=False, indent=1)
        erros = [e for e in r.get('erros', []) if e.get('tipo') == 'E']
        advert = [e for e in r.get('erros', []) if e.get('tipo') == 'A']
        for e in r.get('erros', []):
            m = por_msg[e.get('codigo')]
            m['arquivos'].add(nome)
            m['ocorrencias'] += 1
            m['tipo'] = e.get('tipo')
            m['descricao'] = e.get('descricao') or ''
        top = collections.Counter(f"{e.get('registro')}:{e.get('codigo')}" for e in erros).most_common(3)
        principais = '; '.join(f'{k} x{n}' for k, n in top)
        verif = r.get('verificacoes', [])
        for v in verif:
            achados.append({'arquivo': nome, 'codigo': v['codigo'], 'nivel': v['nivel'], 'titulo': v['titulo'],
                            'quantidade': v['quantidade'], 'valor_total': br(v['valorTotal'])})
        if r.get('resumo'):
            periodos.append((nome, r['resumo']))
        avisos = '; '.join(a.get('mensagem', '') for a in r.get('avisos', []))
        extra = f' | {len(verif)} verificações com achado' if analisar else ''
        print(f"[{i}/{len(arquivos)}] {nome}: {r.get('estado')} ({len(erros)} erros){extra} {principais}", flush=True)
        resumo.append({'arquivo': nome, 'estado': r.get('estado'), 'valido': r.get('valido'), 'erros': len(erros),
                       'advertencias': len(advert), 'achados_malha': len(verif) if analisar else '',
                       'avisos': avisos, 'principais': principais or r.get('falha', '')})

    gravar_csv(os.path.join(saida, 'resumo.csv'),
               ['arquivo', 'estado', 'valido', 'erros', 'advertencias', 'achados_malha', 'avisos', 'principais'], resumo)
    gravar_csv(os.path.join(saida, 'erros-por-mensagem.csv'), ['codigo', 'tipo', 'arquivos', 'ocorrencias', 'descricao'],
               sorted(({'codigo': k, 'tipo': v['tipo'], 'arquivos': len(v['arquivos']), 'ocorrencias': v['ocorrencias'],
                        'descricao': v['descricao']} for k, v in por_msg.items()),
                      key=lambda x: (-x['arquivos'], -x['ocorrencias'])))
    if analisar:
        gravar_csv(os.path.join(saida, 'verificacoes.csv'),
                   ['arquivo', 'codigo', 'nivel', 'titulo', 'quantidade', 'valor_total'], achados)
        quebras = cadeia_saldo_credor(periodos)
        gravar_csv(os.path.join(saida, 'saldo-credor.csv'),
                   ['tipo', 'contribuinte', 'ie', 'mes', 'arquivo', 'saldo_transportado', 'mes_seguinte', 'arquivo_seguinte',
                    'saldo_credor_anterior', 'diferenca'], quebras)
        reais = sum(1 for q in quebras if q['tipo'] == 'quebra')
        print(f'{len(achados)} achados de malha; {reais} quebras na cadeia de saldo credor'
              f' (+{len(quebras) - reais} diferenças de centavos).')
    aprovados = sum(1 for r in resumo if r['valido'])
    print(f'\n{aprovados}/{len(resumo)} aprovados. Detalhes em {saida}/')


if __name__ == '__main__':
    main()

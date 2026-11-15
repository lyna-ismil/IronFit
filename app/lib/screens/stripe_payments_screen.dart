import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../core/network/api_client.dart';
import '../services/stripe_service.dart';
import '../widgets/common_widgets.dart';

class StripePaymentsPage extends StatefulWidget {
  final Map<String, dynamic>? plan;
  const StripePaymentsPage({super.key, this.plan});
  @override
  State<StripePaymentsPage> createState() => _StripePaymentsPageState();
}

class _StripePaymentsPageState extends State<StripePaymentsPage>
    with WidgetsBindingObserver {
  late String _attempt;
  String? _order, _url, _error;
  bool _busy = false, _checking = false;
  Map<String, dynamic>? _status;
  List<Map<String, dynamic>> _history = [];
  Timer? _timer;
  int _polls = 0;
  String get _key => 'stripe_order_${Api.myId}';
  String _newAttempt() => List.generate(
    24,
    (_) => Random.secure().nextInt(16).toRadixString(16),
  ).join();
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _attempt = _newAttempt();
    _order = Api.prefs.getString(_key);
    if (widget.plan != null) {
      _attempt =
          Api.prefs.getString('${_key}_attempt_${widget.plan!['id']}') ??
          _attempt;
    }
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (_order != null && _polls++ < 24) _refresh();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _polls = 0;
      _refresh();
    }
  }

  Future<void> _open(String url) async {
    final uri = Uri.parse(url);
    if (uri.scheme != 'https' ||
        !(uri.host == 'checkout.stripe.com' ||
            uri.host == 'billing.stripe.com' ||
            uri.host == 'invoice.stripe.com' ||
            uri.host == 'pay.stripe.com')) {
      throw Exception('Lien de paiement invalide');
    }
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      throw Exception('Impossible d’ouvrir Stripe. Réessayez.');
    }
  }

  Future<void> _refresh() async {
    if (_checking) return;
    _checking = true;
    try {
      final history = await StripeService.history();
      final status = _order == null
          ? null
          : await StripeService.status(_order!);
      if (!mounted) return;
      setState(() {
        _history = history;
        _status = status;
        _error = null;
      });
      if (status?['status'] == 'paid') {
        await Api.refreshMe();
      }
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      _checking = false;
    }
  }

  Future<void> _checkout() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Api.prefs.setString(
        '${_key}_attempt_${widget.plan!['id']}',
        _attempt,
      );
      final r = await StripeService.checkout(
        widget.plan!['id'] as int,
        _attempt,
      );
      _order = r['order_id'] as String;
      _url = r['url'] as String;
      await Api.prefs.setString(_key, _order!);
      _polls = 0;
      await _open(_url!);
      await _refresh();
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _portal() async {
    setState(() => _busy = true);
    try {
      final r = await StripeService.portal();
      await _open(r['url'] as String);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _status?['status'];
    final samePlan =
        widget.plan == null || _status?['plan_id'] == widget.plan!['id'];
    final label = switch (status) {
      'paid' => 'Paiement confirmé',
      'expired' => 'Session expirée',
      'failed' => 'Paiement échoué',
      'canceled' => 'Abonnement résilié',
      'active' => 'Abonnement actif',
      'past_due' => 'Paiement à régulariser',
      'unpaid' => 'Paiement non réglé',
      _ => 'En attente de confirmation Stripe',
    };
    return Scaffold(
      appBar: AppBar(title: const Text('Paiements en ligne')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 760),
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    'MODE TEST · Projet éducatif. Aucun paiement réel. Utilisez uniquement les cartes de test Stripe.',
                  ),
                ),
              ),
              if (widget.plan != null)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          '${widget.plan!['name']}',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          '${money(widget.plan!['price'], currency: '${widget.plan!['currency'] ?? 'EUR'}')} · ${widget.plan!['duration']}',
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          'Le montant final, les taxes éventuelles et la périodicité sont affichés sur Stripe avant confirmation.',
                        ),
                        const SizedBox(height: 20),
                        FilledButton.icon(
                          onPressed:
                              _busy ||
                                  (samePlan &&
                                      (status == 'paid' || status == 'active'))
                              ? null
                              : _checkout,
                          icon: const Icon(Icons.lock_outline),
                          label: Text(
                            _busy
                                ? 'Préparation…'
                                : 'Payer par carte sur Stripe',
                          ),
                        ),
                        if (samePlan &&
                            (status == 'expired' ||
                                status == 'failed' ||
                                (status == 'paid' &&
                                    _status?['mode'] == 'payment')))
                          TextButton(
                            onPressed: _busy
                                ? null
                                : () {
                                    setState(() {
                                      _attempt = _newAttempt();
                                      _order = null;
                                      _status = null;
                                    });
                                    _checkout();
                                  },
                            child: Text(
                              status == 'paid'
                                  ? 'Renouveler cette formule'
                                  : 'Nouvelle tentative',
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              if (_order != null)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          label,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        if (_status != null) ...[
                          Text('${_status!['plan']}'),
                          if (_status!['amount'] != null)
                            Text(
                              money(
                                _status!['amount'],
                                currency: '${_status!['currency'] ?? 'TND'}',
                              ),
                            ),
                        ],
                        if (status != 'paid')
                          const Text(
                            'Fermer ou annuler Stripe ne valide aucun paiement. L’adhésion est activée uniquement après vérification du serveur.',
                          ),
                        TextButton(
                          onPressed: _refresh,
                          child: const Text('Vérifier le paiement'),
                        ),
                      ],
                    ),
                  ),
                ),
              if (_status?['checkout_status'] == 'open')
                TextButton(
                  onPressed: _busy
                      ? null
                      : () async {
                          setState(() => _busy = true);
                          try {
                            await StripeService.cancel(_order!);
                            await _refresh();
                          } catch (e) {
                            if (mounted) setState(() => _error = '$e');
                          } finally {
                            if (mounted) setState(() => _busy = false);
                          }
                        },
                  child: const Text('Annuler cette tentative'),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(
                    _error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
              OutlinedButton.icon(
                onPressed: _busy ? null : _portal,
                icon: const Icon(Icons.manage_accounts_outlined),
                label: const Text('Gérer mon abonnement et mes factures'),
              ),
              const SizedBox(height: 20),
              Text(
                'Historique Stripe',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              if (_history.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('Aucun paiement Stripe confirmé.'),
                ),
              for (final p in _history)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.receipt_long),
                    title: Text('${p['description']}'),
                    subtitle: Text(
                      '${p['date']} · ${money(p['amount'], currency: '${p['currency'] ?? 'TND'}')} · Confirmé',
                    ),
                    trailing: p['receipt_url'] == null
                        ? null
                        : IconButton(
                            tooltip: 'Facture',
                            icon: const Icon(Icons.open_in_new),
                            onPressed: () async {
                              try {
                                await _open(p['receipt_url'] as String);
                              } catch (e) {
                                if (mounted) setState(() => _error = '$e');
                              }
                            },
                          ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

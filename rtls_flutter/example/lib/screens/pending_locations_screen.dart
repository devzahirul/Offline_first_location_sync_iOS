import 'package:flutter/material.dart';

/// Pending locations list (iOS parity). Plugin does not expose pending points list;
/// shows count and message.
class PendingLocationsScreen extends StatelessWidget {
  const PendingLocationsScreen({
    super.key,
    required this.pendingCount,
    this.onRefresh,
  });

  final int pendingCount;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Pending locations')),
      body: RefreshIndicator(
        onRefresh: onRefresh ?? () async {},
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              '$pendingCount pending (not yet uploaded)',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 16),
            if (pendingCount == 0)
              Text(
                'No pending points.',
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              )
            else
              Text(
                'Full list requires plugin API. Count from getStats().',
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
          ],
        ),
      ),
    );
  }
}

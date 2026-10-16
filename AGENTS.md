# MojaLoss Android implementation

One native app; selected Forest & Porcelain with Sage accents.

Coordinator owns build files, app shell/navigation, core/model and shared contracts.
UI owns core/ui except motion and app/feature screens except player controllers.
Data owns core/data and auth/account/settings state wiring.
Player owns core/player and app/feature/player.
Motion owns core/ui/motion and app/adaptive.
QA owns cross-feature tests and evidence; feature owners may write focused local tests.

Do not edit another owner's files without a coordinator handoff. Keep actual live service
results distinct from fixture/demo data. Do not call the MojaLoss service in development
scripts or tools. Native supported authentication is triggered explicitly by the customer.
No fake ratings, no local-only replacement for service favorites, no failed-seek progress.

